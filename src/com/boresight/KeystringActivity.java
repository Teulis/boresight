package com.boresight;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Samsung keystring catalogue, with a record of what each one actually did.
 *
 * The record is the point. There are 305 of these, a third of them unreachable from a
 * dialpad and others that simply do nothing on a given build, and without somewhere to
 * write that down you rediscover it every time. Marking a code as doing nothing is one
 * tap, and the dead list copies out as text.
 */
public class KeystringActivity extends Screen {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;

    private EditText filter;
    private TextView summary, note, cscLine, cscNote;
    private LinearLayout list;

    private List<Keystrings.Entry> all = new ArrayList<>();
    private int show = SHOW_ALL;
    private static final int SHOW_ALL = 0, SHOW_DIALABLE = 1, SHOW_UNTESTED = 2,
                             SHOW_WORKED = 3, SHOW_DEAD = 4;
    private TextView showButton;
    private boolean resolved;

    @Override protected String title() { return "KEYSTRINGS"; }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();

        LinearLayout head = pane("Dialer codes");
        cscLine = kv(head, "This build", "reading CSC…");
        summary = kv(head, "Catalogue", "loading…");

        filter = new EditText(this);
        filter.setHint("filter by code, note or group");
        filter.setSingleLine(true);
        filter.setTextColor(skin.inkPrimary);
        filter.setHintTextColor(skin.inkSecondary);
        filter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { paint(); }
        });
        head.addView(filter, Theme.wrap());

        LinearLayout row = actionRow(head);
        showButton = smallAction(row, "All", new Runnable() {
            @Override public void run() { show = (show + 1) % 5; paint(); }
        });
        smallAction(row, "Check all", new Runnable() {
            @Override public void run() { resolveAll(); }
        });
        smallAction(row, "Copy dead", new Runnable() {
            @Override public void run() { copyDead(); }
        });
        smallAction(row, "Reset marks", new Runnable() {
            @Override public void run() { confirmReset(); }
        });

        cscNote = hint(head, "");
        note = hint(head, "Tap a code to dial it, then come back and say whether it did "
                + "anything. Samsung's dialer fires a keystring as the last character "
                + "is typed, so it never has to be placed as a call.");

        LinearLayout box = pane("Codes");
        list = th.column(this);
        box.addView(list, Theme.wrap());

        load();
    }

    private void load() {
        pool.execute(new Runnable() {
            @Override public void run() {
                final List<Keystrings.Entry> e = Keystrings.all(KeystringActivity.this);
                final String code = Csc.code(KeystringActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        all = e;
                        paintCsc(code);
                        paint();
                    }
                });
            }
        });
    }

    /**
     * What the carrier build is likely to allow.
     *
     * Reported experience rather than anything this app can test, and labelled that
     * way — a Verizon user should know before dialling thirty dead codes that the
     * firmware is the reason, not their phone.
     */
    private void paintCsc(String code) {
        int outlook = Csc.outlook(code);
        cscLine.setText((code.isEmpty() ? "CSC unknown" : code + "  ·  " + Csc.carrier(code))
                + "  ·  " + Csc.outlookName(outlook));
        cscLine.setTextColor(outlook == Csc.OPEN ? skin.accent(Skin.CH_FREQ)
                : outlook == Csc.LOCKED_DOWN ? skin.accent(Skin.CH_ALERT)
                : outlook == Csc.PARTIAL ? skin.accent(Skin.CH_SAT)
                : skin.inkSecondary);
        cscNote.setText(Csc.note(code) + "  Reported from the field, not measured here.");
    }

    /**
     * Ask the package manager about every code.
     *
     * Off the main thread: 305 queryBroadcastReceivers calls is a visible freeze on
     * the UI thread, and it only needs doing once per visit.
     */
    private void resolveAll() {
        note.setText("checking which codes have a receiver…");
        pool.execute(new Runnable() {
            @Override public void run() {
                for (Keystrings.Entry e : all) Keystrings.resolve(KeystringActivity.this, e);
                resolved = true;
                ui.post(new Runnable() {
                    @Override public void run() {
                        int n = 0;
                        for (Keystrings.Entry e : all) if (e.resolves) n++;
                        note.setText(n + " of " + all.size() + " have a registered "
                                + "receiver on this build. A code that resolves can "
                                + "still do nothing visible — that is what the marks "
                                + "are for.");
                        paint();
                    }
                });
            }
        });
    }

    private boolean keep(Keystrings.Entry e, String q) {
        switch (show) {
            case SHOW_DIALABLE: if (!e.dialable()) return false; break;
            case SHOW_UNTESTED: if (e.result != Keystrings.UNTESTED) return false; break;
            case SHOW_WORKED:   if (e.result != Keystrings.WORKED) return false; break;
            case SHOW_DEAD:     if (e.result != Keystrings.NOTHING) return false; break;
        }
        if (q.isEmpty()) return true;
        return (e.code + " " + e.note + " " + e.group).toLowerCase(Locale.US).contains(q);
    }

    private String showName() {
        switch (show) {
            case SHOW_DIALABLE: return "Dialable";
            case SHOW_UNTESTED: return "Untested";
            case SHOW_WORKED:   return "Worked";
            case SHOW_DEAD:     return "Did nothing";
            default:            return "All";
        }
    }

    private void paint() {
        String q = filter.getText().toString().trim().toLowerCase(Locale.US);
        showButton.setText(showName());

        int worked = 0, dead = 0, undialable = 0;
        for (Keystrings.Entry e : all) {
            if (e.result == Keystrings.WORKED) worked++;
            if (e.result == Keystrings.NOTHING) dead++;
            if (!e.dialable()) undialable++;
        }
        summary.setText(all.size() + " codes  ·  " + undialable + " not dialable  ·  "
                + worked + " worked  ·  " + dead + " did nothing");

        list.removeAllViews();
        String group = null;
        int shown = 0;
        for (final Keystrings.Entry e : all) {
            if (!keep(e, q)) continue;
            if (!e.group.equals(group)) {
                group = e.group;
                TextView cap = th.caption(this, group);
                cap.setTextColor(skin.accent(Skin.CH_RSRP));
                cap.setPadding(0, th.dp(11), 0, th.dp(3));
                list.addView(cap);
            }
            list.addView(rowView(e));
            if (++shown > 400) break;
        }
        if (shown == 0) {
            list.addView(th.text(this, "Nothing matches.", 11, skin.inkSecondary));
        }
        status(shown + " shown", showName());
    }

    private View rowView(final Keystrings.Entry e) {
        LinearLayout box = th.column(this);
        box.setPadding(0, th.dp(6), 0, th.dp(6));
        box.setBackground(th.ripple(th.solid(0x00000000, 0)));
        box.setClickable(true);

        LinearLayout top = th.row(this);
        TextView code = th.num(this, e.dialable() ? e.dialString() : e.code, 13,
                e.dialable() ? skin.accent(Skin.CH_RSRP) : skin.inkSecondary);
        code.setTypeface(skin.face(true, Typeface.BOLD));
        top.addView(code, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (e.result != Keystrings.UNTESTED) {
            TextView mark = th.num(this, Keystrings.resultName(e.result), 10,
                    e.result == Keystrings.WORKED ? skin.accent(Skin.CH_FREQ)
                                                  : skin.accent(Skin.CH_ALERT));
            top.addView(mark);
        }
        box.addView(top, Theme.wrap());

        StringBuilder sub = new StringBuilder();
        if (!e.note.isEmpty()) sub.append(e.note);
        if (!e.dialable()) {
            if (sub.length() > 0) sub.append("   ·   ");
            sub.append("word code — cannot be typed on a dialpad");
        }
        if (resolved) {
            if (sub.length() > 0) sub.append("   ·   ");
            sub.append(e.resolves ? "receiver: " + shortPkg(e.receiver) : "no receiver");
        }
        if (sub.length() > 0) {
            box.addView(th.text(this, sub.toString(), 9,
                    Skin.alpha(skin.inkSecondary, 0.95f)));
        }

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { actions(e); }
        });
        return box;
    }

    private static String shortPkg(String p) {
        if (p == null || p.isEmpty()) return "?";
        int i = p.lastIndexOf('.');
        return i > 0 && i < p.length() - 1 ? p.substring(i + 1) : p;
    }

    private void actions(final Keystrings.Entry e) {
        final List<String> items = new ArrayList<>();
        final List<Runnable> run = new ArrayList<>();

        if (e.dialable()) {
            items.add("Dial " + e.dialString());
            run.add(new Runnable() {
                @Override public void run() {
                    try {
                        Keystrings.dial(KeystringActivity.this, e);
                        toast("Come back and mark what happened");
                    } catch (Throwable t) { toast("No dialer: " + t.getMessage()); }
                }
            });
        } else {
            final String guess = e.keypadGuess();
            if (!guess.isEmpty()) {
                items.add("Try keypad digits *#" + guess + "#");
                run.add(new Runnable() {
                    @Override public void run() {
                        Keystrings.Entry g = new Keystrings.Entry();
                        g.code = guess;
                        try { Keystrings.dial(KeystringActivity.this, g); }
                        catch (Throwable t) { toast("No dialer"); }
                        toast("Unverified guess — Samsung fires these internally");
                    }
                });
            }
        }

        items.add("Mark: worked");
        run.add(new Runnable() {
            @Override public void run() { mark(e, Keystrings.WORKED); }
        });
        items.add("Mark: did nothing");
        run.add(new Runnable() {
            @Override public void run() { mark(e, Keystrings.NOTHING); }
        });
        if (e.result != Keystrings.UNTESTED) {
            items.add("Clear the mark");
            run.add(new Runnable() {
                @Override public void run() { mark(e, Keystrings.UNTESTED); }
            });
        }
        items.add("Copy code");
        run.add(new Runnable() {
            @Override public void run() {
                ClipboardManager cb = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("keystring",
                        e.dialable() ? e.dialString() : e.code));
                toast("Copied");
            }
        });
        items.add("Details");
        run.add(new Runnable() { @Override public void run() { details(e); } });

        Sheet s = Sheet.of(this, e.dialable() ? e.dialString() : e.code);
        for (int i = 0; i < items.size(); i++) {
            final int w = i;
            s.choice(items.get(i), null, false, new Runnable() {
                @Override public void run() { run.get(w).run(); }
            });
        }
        s.close("Cancel").show();
    }

    private void mark(Keystrings.Entry e, int result) {
        e.result = result;
        Keystrings.record(this, e.code, result);
        paint();
        toast(result == Keystrings.UNTESTED ? "Cleared"
                : "Marked " + Keystrings.resultName(result));
    }

    private void details(Keystrings.Entry e) {
        Keystrings.resolve(this, e);
        StringBuilder b = new StringBuilder();
        b.append("code       ").append(e.code).append('\n');
        b.append("group      ").append(e.group).append('\n');
        if (!e.note.isEmpty()) b.append("note       ").append(e.note).append('\n');
        b.append("dialable   ").append(e.dialable() ? "yes" : "no").append('\n');
        if (!e.dialable()) {
            b.append("keypad     *#").append(e.keypadGuess()).append("#  (a guess)\n");
        }
        b.append("receiver   ").append(e.resolves ? e.receiver : "none registered").append('\n');
        b.append("marked     ").append(Keystrings.resultName(e.result)).append("\n\n");
        b.append("Samsung registers these under its own broadcast action, not the AOSP "
               + "one, and that action is signature-level — so this app can see which "
               + "codes exist but cannot fire one. Dialling is the only route, which "
               + "is why a word code is unreachable however real it is.");
        info(e.dialable() ? e.dialString() : e.code, b.toString());
    }

    private void copyDead() {
        String dead = Keystrings.deadList(this);
        if (dead.isEmpty()) { toast("Nothing marked as dead yet"); return; }
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("dead keystrings", dead));
        toast("Copied the dead list");
    }

    private void confirmReset() {
        Sheet.of(this, "Clear every mark?")
                .text("Forgets which codes worked and which did nothing. The "
                    + "catalogue itself is untouched.")
                .action("Clear", true, new Runnable() {
                    @Override public void run() {
                        Keystrings.clearResults(KeystringActivity.this);
                        load();
                    }
                })
                .close("Cancel")
                .show();
    }
}

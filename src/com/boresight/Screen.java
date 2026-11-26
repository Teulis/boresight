package com.boresight;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shared scaffolding for every screen but the live page: theme, skin, the hamburger panel,
 * a scrolling body and a status strip, plus the small set of controls these screens are
 * built from. Keeps the look consistent and the individual screens short.
 */
public abstract class Screen extends Activity {

    protected Theme th;
    protected Skin skin;
    protected Drawer drawer;
    protected Header header;
    protected LinearLayout body;
    protected TextView statusLeft, statusRight;

    /** Screen title, shown in the bar. */
    protected abstract String title();

    /** Fill {@link #body}. Called once, after the scaffold exists. */
    protected abstract void content();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        th = new Theme(this);
        skin = Skin.current(this);

        LinearLayout root = th.column(this);
        root.setBackgroundColor(skin.windowFlat());

        header = new Header(this, th, skin, title(), new Runnable() {
            @Override public void run() { drawer.toggle(); }
        });
        root.addView(header, Theme.wrap());

        // Everything below the title bar. Kept separate so the bar can run under the
        // status bar while the page is inset away from the gesture area.
        LinearLayout page = th.column(this);
        root.addView(page, Theme.grow());

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(Theme.grow());
        body = th.column(this);
        int p = th.dp((int) skin.padding);
        body.setPadding(p, th.dp(6), p, th.dp(18));
        // Keep the ScrollView from jumping to the first focusable child on layout.
        body.setFocusableInTouchMode(true);
        body.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.addView(body);
        page.addView(scroll);
        page.addView(statusBar());

        drawer = Drawer.attach(this, th, skin, root);
        th.useSkin(skin);
        th.systemBars(this);
        Header.inset(this, header, page, th, drawer);
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(skin.windowFlat()));

        content();
    }

    @Override public void onBackPressed() {
        if (drawer != null && drawer.isOpen()) { drawer.close(); return; }
        super.onBackPressed();
    }

    /** Offer an overflow menu in the title bar. Labels are reported back by index. */
    protected void menu(String[] labels, Header.MenuPick pick) {
        if (header != null) header.setMenu(labels, pick);
    }

    private View statusBar() {
        LinearLayout strip = th.row(this);
        strip.setBackgroundColor(skin.header[0]);
        strip.setPadding(th.dp(12), th.dp(6), th.dp(12), th.dp(7));
        strip.setGravity(Gravity.CENTER_VERTICAL);

        statusLeft = th.num(this, "", 10, skin.inkSecondary);
        strip.addView(statusLeft, Theme.weight(1f));
        statusRight = th.num(this, "", 10, skin.inkSecondary);
        statusRight.setGravity(Gravity.END);
        strip.addView(statusRight);

        LinearLayout wrap = th.column(this);
        View rule = new View(this);
        rule.setBackgroundColor(skin.headerRule);
        wrap.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair())));
        wrap.addView(strip, Theme.wrap());
        return wrap;
    }

    protected void status(String left, String right) {
        if (statusLeft != null) statusLeft.setText(left == null ? "" : left);
        if (statusRight != null) statusRight.setText(right == null ? "" : right);
    }

    // ================================================================== building blocks

    /** A framed pane with a caption, returned so callers can add rows into it. */
    protected LinearLayout pane(String caption) {
        LinearLayout box = th.column(this);
        box.setBackground(skin.tileBg(th));
        int pd = th.dp(10);
        box.setPadding(pd, th.dp(8), pd, th.dp(10));
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.setMargins(th.dp(2), th.dp(3), th.dp(2), th.dp((int) skin.sectionSpacing));
        box.setLayoutParams(lp);

        if (caption != null) {
            TextView c = th.caption(this, caption);
            c.setTextColor(skin.accent(Skin.CH_RSRP));
            c.setPadding(0, 0, 0, th.dp(5));
            box.addView(c);
        }
        body.addView(box);
        return box;
    }

    /** Label on the left, value on the right, the way an instrument lists a reading. */
    protected TextView kv(LinearLayout into, String label, String value) {
        LinearLayout row = th.row(this);
        row.setPadding(0, th.dp(3), 0, th.dp(3));
        row.setGravity(Gravity.CENTER_VERTICAL);

        // The label sizes to its own text and the value takes what is left. The other
        // way round — label weighted, value wrapping — collapses the label to nothing
        // when a value is long, and a zero-width TextView wraps one character per line.
        // "Carriers" came out as a vertical column of letters next to a long message.
        TextView l = th.text(this, label, 12, skin.inkSecondary);
        l.setPadding(0, 0, th.dp(10), 0);
        // Not Theme.wrap(): that is MATCH_PARENT wide, which is right when adding to a
        // column and wrong in a row, where it makes the label eat the whole width.
        row.addView(l, Theme.fixed(ViewGroup.LayoutParams.WRAP_CONTENT,
                                   ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = th.num(this, value == null ? "-" : value, 12, skin.inkPrimary);
        v.setTypeface(skin.face(true, Typeface.BOLD));
        v.setGravity(Gravity.END);
        row.addView(v, Theme.weight(1f));

        into.addView(row, Theme.wrap());
        return v;
    }

    protected TextView mono(LinearLayout into, String text, float size, int colour) {
        TextView t = th.num(this, text, size, colour);
        t.setPadding(0, th.dp(2), 0, th.dp(2));
        into.addView(t);
        return t;
    }

    protected TextView hint(LinearLayout into, String text) {
        TextView t = th.text(this, text, 11, Skin.alpha(skin.inkSecondary, 0.9f));
        t.setPadding(0, th.dp(5), 0, th.dp(2));
        t.setLineSpacing(th.dpf(1.5f), 1f);
        into.addView(t);
        return t;
    }

    /** A boolean preference, rendered as a tappable row with a pill. */
    protected void toggle(LinearLayout into, final String label, String explain,
                          final String key, final boolean def, final Runnable after) {
        final LinearLayout row = th.row(this);
        row.setPadding(th.dp(2), th.dp(9), th.dp(2), th.dp(9));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(th.ripple(th.solid(0x00000000, 0)));
        row.setClickable(true);

        LinearLayout text = th.column(this);
        TextView l = th.text(this, label, 13, skin.inkPrimary);
        text.addView(l);
        if (explain != null) {
            TextView e = th.text(this, explain, 10, skin.inkSecondary);
            e.setPadding(0, th.dp(1), th.dp(10), 0);
            text.addView(e);
        }
        row.addView(text, Theme.weight(1f));

        final TextView pill = th.num(this, "", 10, skin.windowFlat());
        pill.setTypeface(skin.face(true, Typeface.BOLD));
        pill.setPadding(th.dp(10), th.dp(4), th.dp(10), th.dp(4));
        row.addView(pill);

        final Runnable paint = new Runnable() {
            @Override public void run() {
                boolean on = Prefs.bool(Screen.this, key, def);
                pill.setText(on ? "ON" : "OFF");
                pill.setTextColor(on ? skin.windowFlat() : skin.inkSecondary);
                pill.setBackground(on
                        ? th.solid(skin.accent(Skin.CH_RSRP), skin.badgeCornerRadius)
                        : skin.badgeBg(th, 0x00000000));
            }
        };
        paint.run();

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.put(Screen.this, key, !Prefs.bool(Screen.this, key, def));
                paint.run();
                if (after != null) after.run();
            }
        });
        into.addView(row, Theme.wrap());
    }

    /** A pick-one preference over int values, shown as a dialog of labels. */
    protected void choice(LinearLayout into, final String label, final String explain,
                          final String key, final int def,
                          final int[] values, final String[] labels,
                          final Runnable after) {
        final LinearLayout row = th.row(this);
        row.setPadding(th.dp(2), th.dp(9), th.dp(2), th.dp(9));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(th.ripple(th.solid(0x00000000, 0)));
        row.setClickable(true);

        LinearLayout text = th.column(this);
        text.addView(th.text(this, label, 13, skin.inkPrimary));
        if (explain != null) {
            TextView e = th.text(this, explain, 10, skin.inkSecondary);
            e.setPadding(0, th.dp(1), th.dp(10), 0);
            text.addView(e);
        }
        row.addView(text, Theme.weight(1f));

        final TextView val = th.num(this, "", 12, skin.accent(Skin.CH_RSRP));
        val.setTypeface(skin.face(true, Typeface.BOLD));
        row.addView(val);

        final Runnable paint = new Runnable() {
            @Override public void run() {
                int cur = Prefs.integer(Screen.this, key, def);
                String s = String.valueOf(cur);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == cur) { s = labels[i]; break; }
                }
                val.setText(s);
            }
        };
        paint.run();

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Sheet s = Sheet.of(Screen.this, label);
                if (explain != null && !explain.isEmpty()) s.text(explain);
                int cur = Prefs.integer(Screen.this, key, def);
                for (int i = 0; i < labels.length; i++) {
                    final int which = i;
                    s.choice(labels[i], null, values[i] == cur, new Runnable() {
                        @Override public void run() {
                            Prefs.put(Screen.this, key, values[which]);
                            paint.run();
                            if (after != null) after.run();
                        }
                    });
                }
                s.close("Cancel").show();
            }
        });
        into.addView(row, Theme.wrap());
    }

    /** A pick-one preference over string values. */
    protected void choiceStr(LinearLayout into, final String label, final String explain,
                             final String key, final String def,
                             final String[] values, final String[] labels,
                             final Runnable after) {
        final LinearLayout row = th.row(this);
        row.setPadding(th.dp(2), th.dp(9), th.dp(2), th.dp(9));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(th.ripple(th.solid(0x00000000, 0)));
        row.setClickable(true);

        LinearLayout text = th.column(this);
        text.addView(th.text(this, label, 13, skin.inkPrimary));
        if (explain != null) {
            TextView e = th.text(this, explain, 10, skin.inkSecondary);
            e.setPadding(0, th.dp(1), th.dp(10), 0);
            text.addView(e);
        }
        row.addView(text, Theme.weight(1f));

        final TextView val = th.num(this, "", 12, skin.accent(Skin.CH_RSRP));
        val.setTypeface(skin.face(true, Typeface.BOLD));
        row.addView(val);

        final Runnable paint = new Runnable() {
            @Override public void run() {
                String cur = Prefs.string(Screen.this, key, def);
                String s = cur;
                for (int i = 0; i < values.length; i++) {
                    if (values[i].equals(cur)) { s = labels[i]; break; }
                }
                val.setText(s);
            }
        };
        paint.run();

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Sheet s = Sheet.of(Screen.this, label);
                if (explain != null && !explain.isEmpty()) s.text(explain);
                String cur = Prefs.string(Screen.this, key, def);
                for (int i = 0; i < labels.length; i++) {
                    final int which = i;
                    s.choice(labels[i], null, values[i].equals(cur), new Runnable() {
                        @Override public void run() {
                            Prefs.put(Screen.this, key, values[which]);
                            paint.run();
                            if (after != null) after.run();
                        }
                    });
                }
                s.close("Cancel").show();
            }
        });
        into.addView(row, Theme.wrap());
    }

    /** A flat action button in the instrument style. */
    protected TextView action(LinearLayout into, String label, final Runnable run) {
        TextView b = th.text(this, label, 13, skin.accent(Skin.CH_RSRP));
        b.setTypeface(skin.face(false, Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(10));
        b.setBackground(th.ripple(
                skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f))));
        b.setClickable(true);
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.setMargins(0, th.dp(5), 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        into.addView(b);
        return b;
    }

    /** A row of buttons sharing the width. */
    protected LinearLayout actionRow(LinearLayout into) {
        LinearLayout r = th.row(this);
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.setMargins(0, th.dp(5), 0, 0);
        r.setLayoutParams(lp);
        into.addView(r);
        return r;
    }

    protected TextView smallAction(LinearLayout row, String label, final Runnable run) {
        TextView b = th.text(this, label, 12, skin.accent(Skin.CH_RSRP));
        b.setTypeface(skin.face(false, Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(th.dp(8), th.dp(9), th.dp(8), th.dp(9));
        b.setBackground(th.ripple(
                skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f))));
        b.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(th.dp(2), 0, th.dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        row.addView(b);
        return b;
    }

    /** A destructive action, coloured so it cannot be mistaken for a normal one. */
    protected TextView danger(LinearLayout into, String label, final String confirm,
                              final Runnable run) {
        TextView b = th.text(this, label, 13, skin.accent(Skin.CH_ALERT));
        b.setTypeface(skin.face(false, Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(10));
        b.setBackground(th.ripple(
                skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_ALERT), 0.12f))));
        b.setClickable(true);
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.setMargins(0, th.dp(5), 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Sheet.of(Screen.this, label)
                     .text(confirm)
                     .action("Do it", true, run)
                     .close("Cancel")
                     .show();
            }
        });
        into.addView(b);
        return b;
    }

    protected void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** Prompt for a line of text. */
    protected void ask(String title, String initial, final Consumer<String> done) {
        final Sheet s = Sheet.of(this, title);
        s.input(null, initial, 1);
        s.action("OK", true, new Runnable() {
            @Override public void run() { done.accept(s.typed()); }
        });
        s.close("Cancel");
        s.show();
    }

    /** Minimal stand-in, since the project targets a language level without java.util.function. */
    public interface Consumer<T> { void accept(T value); }

    protected void info(String title, String message) {
        Sheet.of(this, title).mono(message).close("Close").show();
    }

    protected Graph graph(LinearLayout into, String channel, String caption,
                          float lo, float hi, float step, int heightDp) {
        Graph g = new Graph(this, th, skin, 600)
                .channel(channel)
                .range(lo, hi)
                .grid(step)
                .caption(caption)
                .unit("")
                .value(true);
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.height = th.dp(heightDp);
        lp.setMargins(0, th.dp(5), 0, 0);
        into.addView(g, lp);
        return g;
    }
}

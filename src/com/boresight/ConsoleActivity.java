package com.boresight;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A terminal.
 *
 * This screen used to be a command runner with a grid of buttons: type something, get
 * output, and start again from nothing. That is a useful thing but it is not a shell —
 * {@code cd} did not stick, a variable set on one line was gone by the next, and there
 * was no session to speak of. {@link Term} keeps one {@code sh} alive for as long as
 * the screen is open, and this is the glass in front of it.
 *
 * The layout follows from that. The scrollback takes most of the height, the prompt and
 * the caret sit directly beneath it, and everything that is not typing — the snippets,
 * the capability probe, the backend switch — has moved behind a button. A terminal with
 * two panes of chips above it is a chip screen with a terminal at the bottom.
 *
 * The caret is the system's own, because an EditText already blinks, already drives the
 * keyboard, already handles selection and paste, and already behaves the way the rest of
 * the phone does. Drawing a fake block cursor would look more like a terminal and work
 * less like one.
 */
public class ConsoleActivity extends Screen {

    /** Scrollback cap. Long enough to read a logcat dump, short enough to stay quick. */
    private static final int MAX_LINES = 2000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Term term = new Term();

    private TextView screenText, promptLabel, statusLine;
    private EditText input;
    private ScrollView scroll;
    private TextView stopButton;

    private final StringBuilder buffer = new StringBuilder();
    private final List<String> history = new ArrayList<>();
    private int historyAt = -1;
    /** Pending output, flushed on a timer so a chatty command cannot stall the UI. */
    private final StringBuilder pending = new StringBuilder();
    private boolean flushQueued;

    /** Things that work on any uid, worth having a tap away. */
    private static final String[][] PALETTE = {
            {"id",            "id"},
            {"where am i",    "pwd; ls"},
            {"props",         "getprop | grep -i -E 'model|fingerprint|version.release|baseband'"},
            {"cpu",           "cat /proc/cpuinfo | grep -i -E 'processor|hardware'"},
            {"mem",           "head -6 /proc/meminfo"},
            {"uptime",        "cat /proc/uptime"},
            {"interfaces",    "ip addr"},
            {"routes",        "cat /proc/net/route"},
            {"ping",          "ping -c 4 1.1.1.1"},
            {"rf temps",      "for z in /sys/class/thermal/thermal_zone*; do "
                            + "echo \"$(cat $z/type) $(cat $z/temp)\"; done "
                            + "| grep -v -- -273000"},
            {"cpu freq",      "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"},
            {"storage",       "df -h"},
            {"selinux",       "getenforce"},
            {"packages",      "pm list packages --user 0 | head -30"},
    };

    /** Needs the shell uid. Kept apart so the refusals are not a surprise. */
    private static final String[][] PRIVILEGED = {
            {"dumpsys",  "dumpsys battery"},
            {"settings", "settings get global airplane_mode_on"},
            {"logcat",   "logcat -d -t 20"},
            {"services", "service list | head -30"},
    };

    @Override protected String title() { return "CONSOLE"; }

    @Override protected void content() {
        LinearLayout pane = pane(null);

        statusLine = th.text(this, "starting a shell…", 11, skin.inkSecondary);
        pane.addView(statusLine);

        // The scrollback. Most of the screen, because that is what you are reading.
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(skin.windowFlat());
        screenText = th.num(this, "", 11, skin.inkPrimary);
        screenText.setTypeface(Typeface.MONOSPACE);
        screenText.setTextIsSelectable(true);
        screenText.setPadding(th.dp(8), th.dp(8), th.dp(8), th.dp(8));
        scroll.addView(screenText);

        // Sized against the window that is actually visible, not the whole display.
        //
        // This used to be a flat 52% of the screen height. With the keyboard up — which
        // is the normal state of a terminal — 52% plus the header, the status line and
        // the prompt is taller than what is left, so the prompt and the caret were pushed
        // off the bottom of the screen and you typed blind into a field you could not
        // see. The height is recomputed whenever the visible frame changes, which is
        // exactly when the keyboard opens and closes.
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.52f));
        slp.setMargins(0, th.dp(6), 0, 0);
        pane.addView(scroll, slp);
        fitScrollback();

        // The prompt line: label and caret on one row, so it reads as one thing.
        LinearLayout promptRow = th.row(this);
        promptRow.setGravity(Gravity.CENTER_VERTICAL);
        promptRow.setPadding(th.dp(4), th.dp(6), th.dp(4), 0);

        promptLabel = th.num(this, "$", 12, skin.accent(Skin.CH_FREQ));
        promptLabel.setTypeface(Typeface.MONOSPACE);
        promptRow.addView(promptLabel);

        input = new EditText(this);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setTextColor(skin.inkPrimary);
        input.setHintTextColor(skin.inkSecondary);
        input.setHint("");
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        input.setPadding(th.dp(6), th.dp(2), 0, th.dp(2));
        // VISIBLE_PASSWORD keeps the keyboard from autocorrecting, capitalising or
        // substituting smart quotes — all three of which silently break a command.
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                // Guard against the duplicate the IME sends alongside the action.
                if (e != null && e.getAction() != KeyEvent.ACTION_DOWN) return true;
                submit();
                return true;
            }
        });
        // A hardware or floating keyboard gets real history keys.
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_UP)   { recall(-1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) { recall(1);  return true; }
                if (code == KeyEvent.KEYCODE_ENTER)     { submit();   return true; }
                return false;
            }
        });
        promptRow.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pane.addView(promptRow, Theme.wrap());

        LinearLayout row = actionRow(pane);
        smallAction(row, "↑", new Runnable() {
            @Override public void run() { recall(-1); }
        });
        smallAction(row, "↓", new Runnable() {
            @Override public void run() { recall(1); }
        });
        smallAction(row, "Snippets", new Runnable() {
            @Override public void run() { snippets(); }
        });
        stopButton = smallAction(row, "Restart", new Runnable() {
            @Override public void run() { restart(true); }
        });

        LinearLayout row2 = actionRow(pane);
        smallAction(row2, "Clear", new Runnable() {
            @Override public void run() {
                buffer.setLength(0);
                screenText.setText("");
            }
        });
        smallAction(row2, "Copy", new Runnable() {
            @Override public void run() {
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText(
                        "console", buffer.toString()));
                toast("Copied");
            }
        });
        smallAction(row2, "Backend", new Runnable() {
            @Override public void run() {
                Backend.show(ConsoleActivity.this, 4242, new Runnable() {
                    @Override public void run() { restart(true); }
                });
            }
        });
        smallAction(row2, "What works", new Runnable() {
            @Override public void run() { capabilities(); }
        });

        hint(pane, "One shell, kept open — cd and shell variables persist, which they "
                 + "did not when every command ran on its own. There is no "
                 + "pseudo-terminal, so full-screen programs that expect one (top, vi, "
                 + "less) will not behave; everything that reads and writes plain text "
                 + "does. Restart kills the shell and starts another: without a pty "
                 + "there is no way to send an interrupt, so a hung command costs you "
                 + "the session rather than just itself.");

        restart(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        term.stop();
    }

    // ------------------------------------------------------------------ session

    private void restart(boolean announce) {
        term.stop();
        if (announce) append("\n-- shell restarted --\n");
        String err = term.start(this, new Term.Listener() {
            @Override public void output(final String text) {
                queue(text);
            }
            @Override public void finished(final int exit, final String cwd) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        flush();
                        if (exit > 0) append("[exit " + exit + "]\n");
                        paintPrompt();
                        setBusy(false);
                    }
                });
            }
            @Override public void ended(final String why) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        flush();
                        append("\n-- " + why + " --\n");
                        setBusy(false);
                        statusLine.setText("no shell · press Restart");
                    }
                });
            }
        });
        if (err != null) {
            append(err + "\n");
            statusLine.setText("no shell");
            return;
        }
        statusLine.setText(term.backend());
        paintPrompt();
    }

    private void setBusy(boolean b) {
        if (stopButton != null) stopButton.setText(b ? "Restart •" : "Restart");
    }

    private void paintPrompt() {
        String dir = term.cwd();
        // Home-style shortening. A full path eats the line on a phone, and the tail is
        // the part you are actually looking at.
        if (dir != null && dir.length() > 24) {
            dir = "…" + dir.substring(dir.length() - 23);
        }
        boolean priv = Shell.get(this).privileged();
        promptLabel.setText(dir + (priv ? " #" : " $"));
        promptLabel.setTextColor(skin.accent(priv ? Skin.CH_ALERT : Skin.CH_FREQ));
    }

    /** Lines collected so far when a command spans more than one. */
    private final StringBuilder continued = new StringBuilder();

    private void submit() {
        String typed = input.getText().toString();

        if (continued.length() > 0 && typed.trim().isEmpty()) {
            // An empty line is the way out of a continuation you did not mean to start.
            append("-- cancelled --\n");
            continued.setLength(0);
            input.setText("");
            paintPrompt();
            return;
        }
        if (typed.trim().isEmpty() && continued.length() == 0) {
            append(promptLabel.getText() + " \n");
            return;
        }
        if (!term.alive()) {
            append("-- no shell; press Restart --\n");
            return;
        }

        append(promptLabel.getText() + " " + typed + "\n");
        input.setText("");

        String whole = continued.length() > 0
                ? continued + "\n" + typed
                : typed;

        if (!ShLine.complete(whole)) {
            continued.setLength(0);
            continued.append(whole);
            promptLabel.setText(">");
            promptLabel.setTextColor(skin.accent(Skin.CH_SAT));
            keepFocus();
            return;
        }

        continued.setLength(0);
        history.add(whole);
        historyAt = history.size();
        setBusy(true);
        term.send(whole);
        // setTextIsSelectable(true) on the scrollback makes it focusable in touch
        // mode, so writing to it hands the focus away and the next thing typed goes
        // into the output view instead of the command line. Take it back every time.
        keepFocus();
    }

    /** The command line must own the keyboard, always. */
    private void keepFocus() {
        input.requestFocus();
        input.setSelection(input.getText().length());
    }

    /**
     * Keep the scrollback to whatever height still leaves the prompt on screen.
     *
     * Measured from the window's visible frame, which shrinks when the keyboard opens,
     * rather than from the display height, which does not. The reserve is the header,
     * the status line, the prompt row and the button row; the floor stops the scrollback
     * collapsing to nothing on a short window.
     */
    private void fitScrollback() {
        final View root = getWindow().getDecorView();
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (scroll == null) return;
                android.graphics.Rect visible = new android.graphics.Rect();
                root.getWindowVisibleDisplayFrame(visible);
                int want = Math.max(th.dp(110), visible.height() - th.dp(230));
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                if (lp != null && lp.height != want) {
                    lp.height = want;
                    scroll.setLayoutParams(lp);
                }
            }
        });
    }

    private void recall(int dir) {
        if (history.isEmpty()) return;
        historyAt = Math.max(0, Math.min(history.size(), historyAt + dir));
        String s = historyAt >= history.size() ? "" : history.get(historyAt);
        input.setText(s);
        input.setSelection(s.length());
    }

    // ------------------------------------------------------------------ output

    /**
     * Collect output and paint it on a timer.
     *
     * A command like logcat can produce thousands of lines in a second. Posting each
     * one to the main thread turns the UI into a queue of layout passes and the screen
     * stops responding — including the Restart button, which is the one control that
     * matters when something is flooding.
     */
    private void queue(String text) {
        synchronized (pending) {
            pending.append(text);
            if (flushQueued) return;
            flushQueued = true;
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { flush(); }
        }, 60);
    }

    private void flush() {
        String chunk;
        synchronized (pending) {
            flushQueued = false;
            if (pending.length() == 0) return;
            chunk = pending.toString();
            pending.setLength(0);
        }
        append(chunk);
    }

    private void append(CharSequence s) {
        buffer.append(s);
        trim();
        screenText.setText(buffer.toString());
        scroll.post(new Runnable() {
            @Override public void run() {
                // FOCUS_DOWN scrolls; it must not also move focus into the scrollback,
                // which is selectable and therefore focusable.
                scroll.fullScroll(View.FOCUS_DOWN);
                if (input != null && !input.hasFocus()) keepFocus();
            }
        });
    }

    /** Keep the scrollback bounded, dropping from the top. */
    private void trim() {
        int lines = 0;
        for (int i = buffer.length() - 1; i >= 0; i--) {
            if (buffer.charAt(i) == '\n' && ++lines > MAX_LINES) {
                buffer.delete(0, i + 1);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ sheets

    private void snippets() {
        final boolean priv = Shell.get(this).privileged();
        Sheet s = Sheet.of(this, "Snippets")
                .text("Tap one to put it on the command line and run it.");
        for (final String[] p : PALETTE) {
            s.choice(p[0], p[1], false, new Runnable() {
                @Override public void run() { runNow(p[1]); }
            });
        }
        s.rule();
        s.text(priv
                ? "These need the shell uid, which you have."
                : "These need the shell uid. On the app's own uid they come back "
                + "refused — they are here so you can see the system say no, rather "
                + "than wondering whether the console is broken.");
        for (final String[] p : PRIVILEGED) {
            s.choice(p[0], p[1], false, new Runnable() {
                @Override public void run() { runNow(p[1]); }
            });
        }
        s.show();
    }

    private void runNow(String cmd) {
        input.setText(cmd);
        input.setSelection(cmd.length());
        submit();
    }

    private void capabilities() {
        final Sheet s = Sheet.of(this, "What this device allows")
                .text("Probed by running each command, not assumed — the same command "
                    + "is permitted on one build and refused on the next.");
        StringBuilder b = new StringBuilder();
        try {
            List<Shell.Probe> probes = Shell.get(this).probe(false);
            for (Shell.Probe p : probes) {
                b.append(p.works ? "  ok    " : "  no    ").append(p.name);
                if (!p.works && p.privilegedHelps) b.append("   (needs shell uid)");
                b.append('\n');
                if (p.works && p.sample != null && !p.sample.isEmpty()) {
                    b.append("          ").append(p.sample.trim()).append('\n');
                }
            }
        } catch (Throwable t) {
            b.append("probe failed: ").append(t.getMessage());
        }
        s.mono(b.toString());
        s.action("Probe again", true, new Runnable() {
            @Override public void run() {
                Shell.get(ConsoleActivity.this).forgetProbes();
                Shell.get(ConsoleActivity.this).probe(true);
                capabilities();
            }
        });
        s.show();
    }
}

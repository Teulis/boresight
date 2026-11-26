package com.boresight;

import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's own log, on screen, and a way to get it off the phone.
 *
 * The point is the second half. A colleague who says "it stopped recording this
 * morning" can send a file instead of a description, and the file says which of the
 * several silent paths was taken — a permission that was revoked, a backend that went
 * away, a modem that stopped answering. None of those crash, so none of them leave a
 * trace anywhere else.
 */
public class LogActivity extends Screen {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private TextView body, stats, updateLine;

    @Override protected String title() { return "LOG"; }

    @Override protected void content() {
        LinearLayout act = pane("This log");
        stats = kv(act, "Held", "-");
        LinearLayout row = actionRow(act);
        smallAction(row, "Refresh", new Runnable() {
            @Override public void run() { paint(); }
        });
        smallAction(row, "Save", new Runnable() {
            @Override public void run() { save(); }
        });
        smallAction(row, "Share", new Runnable() {
            @Override public void run() { share(); }
        });
        danger(act, "Clear", "Delete the log kept on this phone?", new Runnable() {
            @Override public void run() { Logs.clear(); paint(); }
        });
        hint(act, "Almost nothing in this app throws. A missing permission, a modem that "
                + "declines to answer, an absent privileged backend — each degrades to a "
                + "dash on screen rather than a crash, which is right for using it and "
                + "useless for diagnosing it. The reasons are written here instead. "
                + "Uncaught exceptions are recorded on the way past, before the process "
                + "dies.");

        LinearLayout up = pane("Version");
        kv(up, "Running", Stamp.VERSION);
        kv(up, "Build", Stamp.COMMIT + "   ·   " + Stamp.BUILT);
        updateLine = kv(up, "Latest", "not checked");
        action(up, "Check for an update", new Runnable() {
            @Override public void run() { checkUpdate(); }
        });
        hint(up, "Only when you press it. There is no background check: the whole "
               + "privacy claim is that nothing leaves the device unless you send it, "
               + "and a silent version ping would make that false. The request carries "
               + "no identifiers — GitHub learns that somebody looked, not who.");

        LinearLayout out = pane("Recent");
        body = mono(out, "", 9.5f, skin.inkSecondary);

        paint();
    }

    @Override protected void onResume() {
        super.onResume();
        paint();
    }

    private void paint() {
        List<String> lines = Logs.lines();
        stats.setText(lines.size() + " lines   ·   "
                + (Logs.sizeOnDisk() / 1024) + " KB on disk");
        StringBuilder b = new StringBuilder();
        // Newest first: the thing that just went wrong should not need a scroll.
        for (int i = lines.size() - 1; i >= 0; i--) b.append(lines.get(i)).append('\n');
        body.setText(b.length() == 0 ? "nothing logged yet" : b.toString());
    }

    private void save() {
        pool.execute(new Runnable() {
            @Override public void run() {
                final String where = writeOut();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (where == null) toast("Could not write the log");
                        else info("Saved", where);
                    }
                });
            }
        });
    }

    /** Write to Downloads, where the file manager and a USB cable can both see it. */
    private String writeOut() {
        try {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            String name = "boresight-log-" + new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date())
                    + ".txt";
            File f = new File(dir, name);
            FileOutputStream o = new FileOutputStream(f);
            try { o.write(Logs.everything().getBytes("UTF-8")); } finally { o.close(); }
            Logs.i("log", "saved to " + f.getAbsolutePath());
            return f.getAbsolutePath();
        } catch (Throwable e) {
            Logs.e("log", "save failed", e);
            return null;
        }
    }

    /**
     * Hand the text to whatever the person wants to send it with.
     *
     * The text itself rather than a file URI: a FileProvider would be the tidy way, and
     * it is one more manifest entry and one more thing to get wrong for a payload that
     * is a few hundred kilobytes of plain text at worst. Save writes the file.
     */
    private void share() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(android.content.Intent.EXTRA_SUBJECT,
                    "Boresight log " + Stamp.VERSION + " " + Stamp.COMMIT);
            i.putExtra(android.content.Intent.EXTRA_TEXT, Logs.text());
            startActivity(android.content.Intent.createChooser(i, "Send log"));
        } catch (Throwable e) {
            toast("Nothing to share it with");
        }
    }

    private void checkUpdate() {
        updateLine.setText("checking…");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Update.Result r = Update.check(LogActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        updateLine.setText(r.summary());
                        paint();
                        if (r.newer && r.page != null) offer(r);
                    }
                });
            }
        });
    }

    private void offer(final Update.Result r) {
        Sheet s = Sheet.of(this, "Update available");
        s.text(r.latest + " has been released. This phone is running " + r.running + ".");
        s.text("Opening the release page downloads an APK you install yourself. Nothing "
             + "is installed automatically and the app does not update itself.");
        s.choice("Open the release page", null, false, new Runnable() {
            @Override public void run() {
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(r.page)));
                } catch (Throwable e) {
                    toast("No browser available");
                }
            }
        });
        s.close("Not now").show();
    }
}

package com.boresight;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The app's own log: what it did, what it refused to do, and why.
 *
 * It exists because of how this app fails. Almost nothing here throws — a permission is
 * missing, a modem declines to answer, a privileged backend is absent, a vendor build
 * refuses a callback — and every one of those paths deliberately degrades to a dash on
 * screen rather than a crash. That is right for the person using it and useless for the
 * person diagnosing it, because a dash does not say which of five reasons produced it.
 * So the reasons are written down.
 *
 * Deliberately not logcat. An app can only read its own logcat without a privileged
 * permission, the buffer is small and shared with the whole system, and it is gone after
 * a reboot — so a colleague reporting "it stopped recording this morning" has nothing to
 * send. This writes to a file the app owns, survives restarts, and can be saved out.
 *
 * Uncaught exceptions are caught on the way past and written before the process dies,
 * then handed to the platform handler so the crash still reports normally. A stack trace
 * that only exists in a dialog nobody screenshotted is a stack trace nobody has.
 */
public final class Logs {

    /** Lines held for the viewer. Older ones are still in the file. */
    private static final int MEMORY_LINES = 1500;

    /** Roll the file past this; one previous generation is kept. */
    private static final long ROLL_BYTES = 512 * 1024;

    public static final String I = "i", W = "w", E = "e";

    private static final Deque<String> recent = new ArrayDeque<>();
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File file;
    private static boolean started;

    private Logs() { }

    // ------------------------------------------------------------------ start

    /**
     * Begin logging. Safe to call more than once; only the first does anything.
     *
     * Takes the application context on purpose: this outlives any activity, and holding
     * an activity here would leak a whole screen for the life of the process.
     */
    public static synchronized void boot(Context c) {
        if (started) return;
        started = true;
        try {
            File dir = new File(c.getApplicationContext().getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            file = new File(dir, "boresight.log");
        } catch (Throwable ignored) { }

        write(I, "app", "started  " + Stamp.VERSION + "  " + Stamp.COMMIT
                + "  built " + Stamp.BUILT);
        write(I, "app", "device  " + android.os.Build.MANUFACTURER + " "
                + android.os.Build.MODEL + "  android " + android.os.Build.VERSION.RELEASE
                + "  api " + android.os.Build.VERSION.SDK_INT);

        final Thread.UncaughtExceptionHandler prior =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    write(E, "crash", "on thread " + t.getName() + "\n" + trace(e));
                } catch (Throwable ignored) { }
                // Hand it on: the platform still gets to report the crash normally.
                if (prior != null) prior.uncaughtException(t, e);
            }
        });
    }

    // ------------------------------------------------------------------ writing

    public static void i(String tag, String msg) { write(I, tag, msg); }
    public static void w(String tag, String msg) { write(W, tag, msg); }
    public static void e(String tag, String msg) { write(E, tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        write(E, tag, msg + "\n" + trace(t));
    }

    /**
     * Record a reason something did not happen.
     *
     * The common case in this app, and the one worth naming separately so it reads as a
     * decision rather than a failure: "no privileged backend" is not an error, it is the
     * answer to why a field is blank.
     */
    public static void why(String tag, String what, String because) {
        write(I, tag, what + " — " + because);
    }

    private static synchronized void write(String level, String tag, String msg) {
        String line = STAMP.format(new Date()) + "  " + level + "  "
                + pad(tag) + "  " + (msg == null ? "null" : msg);

        recent.addLast(line);
        while (recent.size() > MEMORY_LINES) recent.removeFirst();

        // Mirror to logcat so `adb logcat -s boresight` still works while developing.
        if (E.equals(level)) android.util.Log.e("boresight", tag + ": " + msg);
        else if (W.equals(level)) android.util.Log.w("boresight", tag + ": " + msg);
        else android.util.Log.i("boresight", tag + ": " + msg);

        if (file == null) return;
        try {
            if (file.length() > ROLL_BYTES) roll();
            PrintWriter w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), "UTF-8"));
            try { w.println(line); } finally { w.close(); }
        } catch (Throwable ignored) {
            // A log that throws while logging is worse than no log.
        }
    }

    /** Keep one previous generation, so a crash still has the run before it. */
    private static void roll() {
        try {
            File old = new File(file.getParentFile(), "boresight.log.1");
            if (old.exists()) old.delete();
            file.renameTo(old);
        } catch (Throwable ignored) { }
    }

    private static String pad(String tag) {
        if (tag == null) tag = "?";
        if (tag.length() >= 9) return tag.substring(0, 9);
        StringBuilder b = new StringBuilder(tag);
        while (b.length() < 9) b.append(' ');
        return b.toString();
    }

    public static String trace(Throwable t) {
        if (t == null) return "(no exception)";
        StringWriter s = new StringWriter();
        t.printStackTrace(new PrintWriter(s));
        return s.toString();
    }

    // ------------------------------------------------------------------ reading

    /** Newest last, the way a log reads. */
    public static synchronized List<String> lines() {
        return new ArrayList<>(recent);
    }

    public static synchronized int count() { return recent.size(); }

    /** Everything held in memory, oldest first, as one block. */
    public static synchronized String text() {
        StringBuilder b = new StringBuilder();
        for (String s : recent) b.append(s).append('\n');
        return b.toString();
    }

    /** The file on disk, which goes back further than memory. May not exist. */
    public static File fileOnDisk() { return file; }

    public static long sizeOnDisk() {
        long n = 0;
        try {
            if (file != null && file.exists()) n += file.length();
            File old = file == null ? null : new File(file.getParentFile(), "boresight.log.1");
            if (old != null && old.exists()) n += old.length();
        } catch (Throwable ignored) { }
        return n;
    }

    public static synchronized void clear() {
        recent.clear();
        try {
            if (file != null && file.exists()) file.delete();
            File old = file == null ? null : new File(file.getParentFile(), "boresight.log.1");
            if (old != null && old.exists()) old.delete();
        } catch (Throwable ignored) { }
        write(I, "app", "log cleared");
    }

    /**
     * Everything on disk, newest generation last, for saving out. Falls back to what is
     * in memory when the file could not be opened at all.
     */
    public static String everything() {
        StringBuilder b = new StringBuilder();
        try {
            File old = file == null ? null : new File(file.getParentFile(), "boresight.log.1");
            if (old != null && old.exists()) b.append(read(old));
            if (file != null && file.exists()) b.append(read(file));
        } catch (Throwable ignored) { }
        if (b.length() == 0) return text();
        return b.toString();
    }

    private static String read(File f) {
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int got = 0;
                while (got < buf.length) {
                    int r = in.read(buf, got, buf.length - got);
                    if (r < 0) break;
                    got += r;
                }
                return new String(buf, 0, got, "UTF-8");
            } finally { in.close(); }
        } catch (Throwable e) {
            return "";
        }
    }
}

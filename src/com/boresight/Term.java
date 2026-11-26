package com.boresight;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * One shell, held open.
 *
 * The console screen used to run every command as its own {@code sh -c ...}, which is
 * fine for a probe and wrong for a terminal: each command started in a fresh process,
 * so {@code cd} did nothing, a variable set on one line was gone on the next, and
 * there was no such thing as a session. This keeps a single {@code sh} alive and feeds
 * it lines, which is what makes it a shell rather than a command runner.
 *
 * What this is not, and the UI says so: there is no pseudo-terminal. Android does not
 * hand one to an ordinary app without native code, and without a pty the shell is not
 * interactive in the kernel's sense. The consequences are specific rather than vague —
 * no job control, no Ctrl-C to the foreground process, no line editing from the shell
 * itself, and full-screen programs that expect a terminal (top, vi, less) will either
 * refuse or produce nonsense. Everything that reads stdin and writes stdout works.
 *
 * Knowing when a command has finished is the other thing a pty would have given us.
 * Instead each command is followed by a marker line carrying the exit status and the
 * working directory, so the prompt can show both and the screen knows when to hand the
 * keyboard back. The marker uses a random nonce for the session, because a command
 * that printed the marker text itself would otherwise end the command early — unlikely,
 * but the kind of thing that is baffling when it happens.
 */
public final class Term {

    public interface Listener {
        /** Output arrived. Always called on a background thread. */
        void output(String text);
        /** A command finished: its exit status and the shell's working directory. */
        void finished(int exit, String cwd);
        /** The shell died or could not be started. */
        void ended(String why);
    }

    private final String nonce =
            Long.toHexString((long) (Math.random() * 0xFFFFFFFFL)) + "Z";
    private final String marker = "\u0001BS" + nonce;

    private Process proc;
    private Writer in;
    private Listener listener;
    private volatile boolean running;
    private volatile boolean busy;
    private String backend = "app";
    private String cwd = "/";

    public boolean alive() { return running; }
    public boolean busy() { return busy; }
    public String backend() { return backend; }
    public String cwd() { return cwd; }

    /**
     * Start the shell, preferring the privileged backend.
     *
     * @return null on success, or why it could not start
     */
    public synchronized String start(Context c, Listener l) {
        stop();
        listener = l;

        Shell sh = Shell.get(c);
        Process p = null;
        if (sh.privileged()) {
            try {
                java.lang.reflect.Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                // No -c: a bare sh reads commands from stdin for as long as we keep it
                // open, which is the whole point.
                p = (Process) m.invoke(null,
                        (Object) new String[]{"sh"}, null, null);
                backend = "shell uid, via Shizuku";
            } catch (Throwable t) {
                p = null;
            }
        }
        if (p == null) {
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh");
                pb.redirectErrorStream(true);
                p = pb.start();
                backend = "app sandbox, uid " + android.os.Process.myUid();
            } catch (Throwable t) {
                return "could not start a shell: " + t.getMessage();
            }
        }

        proc = p;
        running = true;
        try {
            in = new OutputStreamWriter(p.getOutputStream());
        } catch (Throwable t) {
            running = false;
            return "shell started but its input could not be opened";
        }

        pump(p.getInputStream(), false);
        // Shizuku's remote process does not merge the streams; the app one does, and
        // reading an already-merged stream twice is harmless.
        try { pump(p.getErrorStream(), true); } catch (Throwable ignored) { }

        // Settle the prompt: find out where we are without the user typing anything.
        send("");
        return null;
    }

    private void pump(final java.io.InputStream s, final boolean stderr) {
        if (s == null) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader r = new BufferedReader(new InputStreamReader(s), 8192);
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int at = line.indexOf(marker);
                        if (at < 0) {
                            emit(line + "\n");
                            continue;
                        }
                        // Anything printed before the marker on the same line is real
                        // output from a command that did not end with a newline.
                        if (at > 0) emit(line.substring(0, at));
                        done(line.substring(at + marker.length()));
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { r.close(); } catch (Throwable ignored) { }
                    if (!stderr) end("the shell exited");
                }
            }
        }, stderr ? "term-err" : "term-out");
        t.setDaemon(true);
        t.start();
    }

    /** Marker payload is "\u0001<exit>\u0001<cwd>". */
    private void done(String tail) {
        int exit = -1;
        String dir = cwd;
        try {
            String[] parts = tail.split("\u0001");
            // parts[0] is empty, [1] exit, [2] cwd
            if (parts.length > 1) exit = Integer.parseInt(parts[1].trim());
            if (parts.length > 2 && !parts[2].trim().isEmpty()) dir = parts[2].trim();
        } catch (Throwable ignored) { }
        cwd = dir;
        busy = false;
        Listener l = listener;
        if (l != null) l.finished(exit, dir);
    }

    private void emit(String s) {
        Listener l = listener;
        if (l != null) l.output(s);
    }

    private void end(String why) {
        if (!running) return;
        running = false;
        busy = false;
        Listener l = listener;
        if (l != null) l.ended(why);
    }

    /**
     * Run a line.
     *
     * The marker is printed with a leading newline so it always starts a line of its
     * own, even when the command's last line of output had none — otherwise the marker
     * and the output share a line and the output's tail is swallowed.
     */
    public synchronized boolean send(String line) {
        if (!running || in == null) return false;
        busy = true;
        try {
            in.write(line);
            in.write("\n");
            in.write("printf '\\n\\001BS" + nonce + "\\001%d\\001%s\\001\\n' \"$?\" \"$PWD\"\n");
            in.flush();
            return true;
        } catch (Throwable t) {
            end("could not write to the shell: " + t.getMessage());
            return false;
        }
    }

    /**
     * Stop whatever is running.
     *
     * Without a pty there is no way to deliver an interrupt to the foreground process,
     * so this kills the shell outright. The caller is expected to start a new one and
     * say plainly that the session was restarted rather than interrupted — pretending
     * a kill is a Ctrl-C would lose a shell's worth of state without explanation.
     */
    public synchronized void stop() {
        running = false;
        busy = false;
        listener = null;
        try { if (in != null) in.close(); } catch (Throwable ignored) { }
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) { }
        in = null;
        proc = null;
    }
}

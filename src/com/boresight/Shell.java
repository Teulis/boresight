package com.boresight;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Command execution, with the privileged path treated as a bonus rather than a
 * requirement.
 *
 * The app runs every feature on its own uid and is expected to work that way forever.
 * Shizuku, when it happens to be installed and running, raises the backend to the shell
 * uid and a handful of features get better. Nothing is gated behind it: a feature either
 * works unprivileged, or it says plainly what it would need and carries on.
 *
 * That distinction is enforced structurally. Callers ask {@link #exec} for a result and
 * {@link #probe} for what this device actually allows; neither mentions a backend. Adding
 * the Shizuku path meant adding one {@link Executor} and changing nothing else, which is
 * the property to preserve.
 *
 * One thing this does not do, and cannot: a privileged backend does not privilege the
 * process. Commands handed to Shizuku run as uid 2000, but java.io.File, the telephony
 * APIs and everything else still run as the app. Code that wants the wider view has to
 * route through here explicitly.
 */
public final class Shell {

    /** The Shizuku app's package, used only to notice whether it is present. */
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    public static final class Result {
        public int exit = -1;
        public String out = "";
        public boolean ran;
        public boolean ok() { return ran && exit == 0; }
        public String firstLine() {
            int i = out.indexOf('\n');
            return i < 0 ? out.trim() : out.substring(0, i).trim();
        }
    }

    /** A way of running a command. Implementations must never throw. */
    public interface Executor {
        String name();
        /** Short description of the privilege level, shown to the user. */
        String describe();
        boolean available();
        Result exec(String command, long timeoutSec);
    }

    // ------------------------------------------------------------------ app backend

    /**
     * Always available. Runs as the app's own uid, which is enough for the network
     * tools, sysfs and system properties, and not enough for the service layer.
     */
    public static final class AppExecutor implements Executor {
        private volatile Process live;

        @Override public String name() { return "app"; }

        @Override public String describe() {
            return "uid " + android.os.Process.myUid() + " · app sandbox";
        }

        @Override public boolean available() { return true; }

        /** Kill whatever is running. The reader then sees end of stream and unwinds. */
        public void cancel() {
            Process p = live;
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }

        @Override public Result exec(String command, long timeoutSec) {
            Result r = new Result();
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                pb.redirectErrorStream(true);
                p = pb.start();
                live = p;
                StringBuilder b = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String s;
                int lines = 0;
                while ((s = br.readLine()) != null) {
                    b.append(s).append('\n');
                    if (++lines > 2000) { b.append("-- truncated --\n"); break; }
                }
                br.close();
                r.ran = true;
                r.out = b.toString();
                r.exit = p.waitFor(timeoutSec, TimeUnit.SECONDS) ? p.exitValue() : -2;
                if (r.exit == -2) r.out += "-- timed out --\n";
            } catch (Exception e) {
                r.out = String.valueOf(e.getMessage());
            } finally {
                if (p != null) p.destroy();
                live = null;
            }
            return r;
        }
    }

    /**
     * The privileged path, through Shizuku's own API.
     *
     * Every entry point is wrapped: the library may be absent at runtime, the service
     * may not be running, permission may not be granted, and the app has to behave
     * identically in all of those cases. A throw here would turn an optional
     * enhancement into a crash, so nothing is allowed to escape.
     *
     * newProcess is private in the published API jar, so it is reached by reflection.
     * What comes back is a ShizukuRemoteProcess, which extends java.lang.Process, so
     * the stream handling is the same as the unprivileged path.
     */
    public static final class ShizukuExecutor implements Executor {
        private final boolean installed;
        private volatile Process live;

        public ShizukuExecutor(Context c) {
            boolean found;
            try {
                c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
                found = true;
            } catch (Throwable e) {
                found = false;
            }
            installed = found;
        }

        public boolean installed() { return installed; }

        /** Service running and reachable, regardless of whether we may use it. */
        public boolean serviceRunning() {
            try { return rikka.shizuku.Shizuku.pingBinder(); }
            catch (Throwable t) { return false; }
        }

        public boolean permitted() {
            try {
                return rikka.shizuku.Shizuku.checkSelfPermission()
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) { return false; }
        }

        /** Ask Shizuku for access. Needs an activity because it shows a dialog. */
        public void requestPermission(int requestCode) {
            try { rikka.shizuku.Shizuku.requestPermission(requestCode); }
            catch (Throwable ignored) {}
        }

        public int remoteUid() {
            try { return rikka.shizuku.Shizuku.getUid(); }
            catch (Throwable t) { return -1; }
        }

        public int serviceVersion() {
            try { return rikka.shizuku.Shizuku.getVersion(); }
            catch (Throwable t) { return -1; }
        }

        @Override public String name() { return "shizuku"; }

        @Override public String describe() {
            if (!installed) return "not installed";
            if (!serviceRunning()) {
                return "installed, service not running — start it from the Shizuku app";
            }
            if (!permitted()) return "service running, permission not granted yet";
            int uid = remoteUid();
            return "uid " + uid + (uid == 2000 ? " · shell, via Shizuku" : " · via Shizuku");
        }

        @Override public boolean available() {
            return installed && serviceRunning() && permitted();
        }

        @Override public Result exec(String command, long timeoutSec) {
            Result r = new Result();
            if (!available()) {
                r.out = "the privileged backend is not available\n";
                return r;
            }
            Process p = null;
            try {
                java.lang.reflect.Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                p = (Process) m.invoke(null,
                        (Object) new String[]{"sh", "-c", command}, null, null);
                if (p == null) { r.out = "no process returned\n"; return r; }
                live = p;

                StringBuilder b = new StringBuilder();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                String s;
                int lines = 0;
                while ((s = br.readLine()) != null) {
                    b.append(s).append('\n');
                    if (++lines > 2000) { b.append("-- truncated --\n"); break; }
                }
                br.close();
                // Standard error separately: the remote process does not merge them.
                try {
                    BufferedReader er = new BufferedReader(
                            new InputStreamReader(p.getErrorStream()));
                    while ((s = er.readLine()) != null) b.append(s).append('\n');
                    er.close();
                } catch (Throwable ignored) {}

                r.ran = true;
                r.out = b.toString();
                r.exit = p.waitFor();
            } catch (Throwable e) {
                r.out = "privileged call failed: " + e + "\n";
            } finally {
                if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
                live = null;
            }
            return r;
        }

        public void cancel() {
            Process p = live;
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ singleton

    private static Shell instance;

    public static synchronized Shell get(Context c) {
        if (instance == null) instance = new Shell(c.getApplicationContext());
        return instance;
    }

    private final AppExecutor app = new AppExecutor();
    private final ShizukuExecutor shizuku;
    private List<Probe> cached;

    private Shell(Context c) {
        shizuku = new ShizukuExecutor(c);
    }

    /** Whichever backend is actually usable, preferring the privileged one. */
    public Executor current() {
        return shizuku.available() ? shizuku : app;
    }

    public boolean privileged() { return shizuku.available(); }
    public boolean shizukuInstalled() { return shizuku.installed(); }
    public ShizukuExecutor shizuku() { return shizuku; }
    public String shizukuStatus() { return shizuku.describe(); }
    public String backendDescription() { return current().describe(); }

    /**
     * Short enough for a menu subtitle or a status bar: what the app can currently do,
     * not which product is providing it.
     */
    public String backendShort() {
        if (privileged()) return "shell uid, via Shizuku";
        if (shizuku.installed()) {
            return shizuku.serviceRunning() ? "app uid · Shizuku not granted"
                                            : "app uid · Shizuku not running";
        }
        return "app uid";
    }

    /** Stop whatever command is in flight, on whichever backend started it. */
    public void cancel() {
        app.cancel();
        shizuku.cancel();
    }

    public Result exec(String command) { return exec(command, 25); }

    public Result exec(String command, long timeoutSec) {
        return current().exec(command, timeoutSec);
    }

    /** Run specifically unprivileged, for comparing what the two backends can see. */
    public Result execAsApp(String command) { return app.exec(command, 25); }

    // ------------------------------------------------------------------ probing

    /**
     * One capability, as measured rather than assumed. Devices differ: the same command
     * is permitted on one build and refused on the next, so nothing here is hardcoded.
     */
    public static final class Probe {
        public final String name, command, ifMissing;
        /**
         * Whether a privileged backend is what this one is waiting on. Kept apart from
         * {@link #ifMissing} so the text can never offer Shizuku as the fix while
         * Shizuku is already running — the caller decides whether to say it.
         */
        public final boolean privilegedHelps;
        public boolean works;
        public String sample = "";

        Probe(String name, String command, String ifMissing) {
            this(name, command, ifMissing, false);
        }

        Probe(String name, String command, String ifMissing, boolean privilegedHelps) {
            this.name = name;
            this.command = command;
            this.ifMissing = ifMissing;
            this.privilegedHelps = privilegedHelps;
        }
    }

    private static List<Probe> definitions() {
        List<Probe> p = new ArrayList<>();
        p.add(new Probe("Run commands", "id",
                "nothing here will work"));
        p.add(new Probe("System properties", "getprop ro.product.model",
                "no device identification"));
        p.add(new Probe("CPU frequency", "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
                "no per-core frequency graphs"));
        p.add(new Probe("Thermal zones", "cat /sys/class/thermal/thermal_zone0/temp",
                "no PA or modem temperatures"));
        p.add(new Probe("ICMP ping", "ping -c 1 -W 2 127.0.0.1",
                "round trip timing falls back to a TCP handshake"));
        p.add(new Probe("Package list", "pm list packages --user 0",
                "cannot enumerate installed apps"));
        p.add(new Probe("Socket table", "cat /proc/net/tcp6",
                "no connection list"));
        p.add(new Probe("System services", "dumpsys battery",
                "no dumpsys at all: battery, telephony, netstats and carrier config "
              + "are all behind it", true));
        p.add(new Probe("Settings database", "settings get global airplane_mode_on",
                "system settings cannot be read or written", true));
        // File reach. Measured, because the rules changed in Android 10 and again in
        // 11, and what a file manager can touch is the whole question behind browsing
        // another app's saves.
        p.add(new Probe("Shared storage", "ls /sdcard",
                "no access to Downloads, DCIM or Documents"));
        p.add(new Probe("Other apps' external data", "ls /sdcard/Android/data/com.android.chrome",
                "blocked to apps since Android 11, and it is where many apps keep "
              + "their saves", true));
        p.add(new Probe("Other apps' media dir", "ls /sdcard/Android/media",
                "no access to the one external folder apps may share"));
        p.add(new Probe("Other apps' private data", "ls /data/data",
                "impossible without root — shell and Shizuku are refused here too, so "
              + "no amount of privilege short of an unlocked bootloader opens it"));
        p.add(new Probe("System partition", "ls /system/bin/sh",
                "cannot inspect system binaries"));
        p.add(new Probe("Log access", "logcat -d -t 1",
                "no log access at all", true));
        return p;
    }

    /** Run every probe once and remember the answer. */
    public synchronized List<Probe> probe(boolean refresh) {
        if (cached != null && !refresh) return cached;
        List<Probe> list = definitions();
        for (Probe p : list) {
            Result r = exec(p.command, 6);
            // Exit status alone is unreliable here — some refusals exit zero and print
            // the complaint — so an empty or obviously-refused body counts as failure.
            String body = r.out == null ? "" : r.out.trim();
            // Case matters: the kernel writes "Permission denied" and the framework
            // writes "Permission Denial", and matching only one of them reported a
            // refusal as a success.
            String lower = body.toLowerCase(java.util.Locale.US);
            boolean refused = body.isEmpty()
                    || lower.contains("permission den")
                    || lower.contains("securityexception")
                    || lower.contains("can't find service")
                    || lower.contains("not permitted")
                    || lower.contains("no such file")
                    || lower.contains("inaccessible");
            p.works = r.ran && !refused;
            p.sample = body.length() > 70 ? body.substring(0, 70) + "…" : body;
            if (p.sample.indexOf('\n') > 0) p.sample = p.sample.substring(0, p.sample.indexOf('\n'));
            // logcat succeeds unprivileged but returns only this app's own entries, so
            // a bare "yes" would overstate it.
            if (p.command.startsWith("logcat") && p.works && !privileged()) {
                p.sample = "this app's entries only · " + p.sample;
            }
        }
        cached = list;
        return cached;
    }

    public synchronized void forgetProbes() { cached = null; }

    /** How many of the probed capabilities currently work. */
    public int workingCount() {
        int n = 0;
        for (Probe p : probe(false)) if (p.works) n++;
        return n;
    }
}

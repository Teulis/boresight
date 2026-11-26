package com.boresight;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One way to read a file, regardless of which uid is allowed to read it.
 *
 * Every screen was growing its own version of the same dance — try java.io, notice it
 * failed, shell out, parse the result — and each copy learned the lesson separately and
 * slightly differently. Worse, the honest "not readable on this build" messages that
 * came out of it were technically true and practically useless: the data was right
 * there, one backend call away.
 *
 * So the rule is now: ask for the path, get the contents. Callers do not choose a uid
 * and do not handle the refusal. Direct access is tried first because it costs a
 * syscall rather than a process, and the fallback runs only when it has to.
 *
 * Two things keep that from being wasteful:
 *
 *  - A path that has already refused a direct read is remembered, so the doomed attempt
 *    is not repeated on every tick of a one-second loop.
 *  - {@link #readMany} fetches a whole set of files in a single backend round trip,
 *    which is what a sampler wants: one process per second, not one per file.
 */
public final class Vfs {

    /**
     * Paths known to need the backend. Keyed by path, so a file that becomes readable
     * after a permission change is only retried when the app restarts — an acceptable
     * trade for not paying a failed open every second, forever.
     */
    private static final Map<String, Boolean> needsBackend = new ConcurrentHashMap<>();

    /** Delimiter for {@link #readMany}. Long and unlikely enough not to occur in /proc. */
    private static final String SEP = "@@BORESIGHT-VFS-SPLIT@@";

    private Vfs() { }

    private static boolean allowed(Context c) {
        return Prefs.bool(c, Prefs.BACKEND_READS, true);
    }

    // ------------------------------------------------------------------ text

    /** Contents of a text file, by whatever route works. Empty string if nothing can. */
    public static String text(Context c, String path) {
        if (!Boolean.TRUE.equals(needsBackend.get(path))) {
            String direct = readDirect(path);
            // null means the open was refused; "" means it opened and was empty. Only
            // the first is a reason to go to the backend — treating an empty file as a
            // refusal would shell out for it forever, every tick, for nothing.
            if (direct != null) return direct;
            needsBackend.put(path, Boolean.TRUE);
        }
        if (!allowed(c)) return "";
        // 2>/dev/null and an exit check, both deliberately. Shell merges stderr into
        // stdout, so without this a refused read returns the string
        // "cat: /sys/...: Permission denied" as if it were the file's contents — and
        // callers dutifully render it as a value. That shipped once; it is the whole
        // reason this helper exists rather than each screen calling exec itself.
        Shell.Result r = Shell.get(c).exec("cat " + quote(path) + " 2>/dev/null", 8);
        if (!r.ran || r.exit != 0 || r.out == null) return "";
        return looksLikeError(r.out) ? "" : r.out;
    }

    /** Last-ditch guard for builds whose shell still leaks the complaint to stdout. */
    private static boolean looksLikeError(String s) {
        String t = s.trim();
        if (t.isEmpty()) return false;
        String l = t.toLowerCase(Locale.US);
        return (l.startsWith("cat:") || l.startsWith("ls:") || l.startsWith("stat:"))
                && (l.contains("permission denied") || l.contains("no such file")
                 || l.contains("is a directory"));
    }

    /**
     * Several files in one go.
     *
     * The direct reads are attempted individually, and only the ones that fail are
     * bundled into a single shell call. A task manager sampling four /proc files a
     * second pays one process instead of four, and nothing at all once they all read
     * directly.
     *
     * @return contents in the same order as the paths; an entry is "" if unreadable
     */
    public static String[] readMany(Context c, String... paths) {
        String[] out = new String[paths.length];
        List<Integer> missing = new ArrayList<>();

        for (int i = 0; i < paths.length; i++) {
            if (!Boolean.TRUE.equals(needsBackend.get(paths[i]))) {
                String direct = readDirect(paths[i]);
                if (direct != null) { out[i] = direct; continue; }
                needsBackend.put(paths[i], Boolean.TRUE);
            }
            out[i] = "";
            missing.add(i);
        }
        if (missing.isEmpty() || !allowed(c)) return out;

        StringBuilder cmd = new StringBuilder();
        for (int k = 0; k < missing.size(); k++) {
            if (k > 0) cmd.append("echo '").append(SEP).append("'; ");
            cmd.append("cat ").append(quote(paths[missing.get(k)])).append(" 2>/dev/null; ");
        }
        Shell.Result r = Shell.get(c).exec(cmd.toString(), 12);
        if (!r.ran || r.out == null) return out;

        String[] parts = r.out.split(java.util.regex.Pattern.quote(SEP) + "\\s*\\n?", -1);
        for (int k = 0; k < missing.size() && k < parts.length; k++) {
            out[missing.get(k)] = parts[k];
        }
        return out;
    }

    /** @return the contents, or null when the file could not be opened at all */
    private static String readDirect(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            return b.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
    }

    /** First line only, trimmed. Convenient for the many sysfs files that hold one value. */
    public static String line(Context c, String path) {
        String s = text(c, path);
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    public static int integer(Context c, String path, int fallback) {
        try { return Integer.parseInt(line(c, path)); }
        catch (Exception e) { return fallback; }
    }

    // ------------------------------------------------------------------ bytes

    /**
     * Raw bytes, up to a limit.
     *
     * The backend route goes through base64 rather than straight through the pipe,
     * because a save file is binary and a shell mangles it at the first newline or NUL.
     */
    public static byte[] bytes(Context c, File f, int limit) {
        if (!Boolean.TRUE.equals(needsBackend.get(f.getAbsolutePath()))) {
            try {
                long n = Math.min(f.length(), limit);
                if (n > 0) {
                    byte[] buf = new byte[(int) n];
                    FileInputStream in = new FileInputStream(f);
                    try {
                        int got = 0, k;
                        while (got < buf.length
                                && (k = in.read(buf, got, buf.length - got)) > 0) got += k;
                        if (got == buf.length) return buf;
                        byte[] t = new byte[got];
                        System.arraycopy(buf, 0, t, 0, got);
                        return t;
                    } finally { in.close(); }
                }
            } catch (Throwable ignored) { }
            needsBackend.put(f.getAbsolutePath(), Boolean.TRUE);
        }
        if (!allowed(c)) return new byte[0];
        Shell.Result r = Shell.get(c).exec("head -c " + limit + " "
                + quote(f.getAbsolutePath()) + " 2>/dev/null | base64", 30);
        String b64 = r.out == null ? "" : r.out.replaceAll("\\s", "");
        if (b64.isEmpty()) return new byte[0];
        try { return android.util.Base64.decode(b64, android.util.Base64.DEFAULT); }
        catch (Throwable t) { return new byte[0]; }
    }

    // ------------------------------------------------------------------ directories

    /** One entry in a directory, however it was discovered. */
    public static final class Item {
        public String name, perms = "";
        public boolean dir, readable = true;
        public long size, modified;
    }

    /**
     * List a directory, falling back to the backend.
     *
     * The subtlety that cost time the first time round: under /sdcard/Android/data an
     * app gets an *empty array*, not null, so a null check never reaches the fallback.
     * An empty result is therefore treated as a possible refusal and re-asked.
     */
    public static List<Item> list(Context c, File dir) {
        List<Item> out = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids != null && kids.length > 0) {
            for (File f : kids) {
                Item i = new Item();
                i.name = f.getName();
                i.dir = f.isDirectory();
                i.size = f.length();
                i.modified = f.lastModified();
                i.readable = f.canRead();
                out.add(i);
            }
            sort(out);
            return out;
        }
        if (!allowed(c)) return out;

        Shell.Result r = Shell.get(c).exec(
                "ls -la " + quote(dir.getAbsolutePath()) + " 2>/dev/null", 10);
        if (r.ran && r.exit == 0 && r.out != null
                && !r.out.toLowerCase(Locale.US).contains("permission den")) {
            for (String s : r.out.split("\n")) {
                Item i = parseLs(s);
                if (i != null) out.add(i);
            }
        }
        sort(out);
        return out;
    }

    private static void sort(List<Item> list) {
        Collections.sort(list, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static Item parseLs(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("total ")) return null;
        String[] p = s.split("\\s+", 8);
        if (p.length < 8) return null;
        Item i = new Item();
        i.perms = p[0];
        i.dir = s.charAt(0) == 'd';
        i.readable = p[0].indexOf('r') > 0;
        try { i.size = Long.parseLong(p[4]); } catch (Exception ignored) { }
        i.name = p[7];
        if (".".equals(i.name) || "..".equals(i.name)) return null;
        // A symlink line ends in "name -> target"; keep only the name.
        int arrow = i.name.indexOf(" -> ");
        if (arrow > 0) i.name = i.name.substring(0, arrow);
        return i;
    }

    // ------------------------------------------------------------------ facts

    /** Size, asking the backend when the direct call reports nothing. */
    public static long size(Context c, File f) {
        long n = f.length();
        if (n > 0) return n;
        if (!allowed(c)) return 0;
        try {
            Shell.Result r = Shell.get(c).exec("stat -c %s "
                    + quote(f.getAbsolutePath()) + " 2>/dev/null", 6);
            if (!r.ran || r.exit != 0) return 0;
            return Long.parseLong(r.firstLine().trim());
        } catch (Exception e) { return 0; }
    }

    public static boolean isDirectory(Context c, File f) {
        if (f.isDirectory()) return true;
        if (f.exists() || !allowed(c)) return false;
        Shell.Result r = Shell.get(c).exec("[ -d " + quote(f.getAbsolutePath())
                + " ] && echo y", 6);
        return "y".equals(r.firstLine().trim());
    }

    public static boolean exists(Context c, File f) {
        if (f.exists()) return true;
        if (!allowed(c)) return false;
        Shell.Result r = Shell.get(c).exec("[ -e " + quote(f.getAbsolutePath())
                + " ] && echo y", 6);
        return "y".equals(r.firstLine().trim());
    }

    /** Forget what we learned, so a permission change takes effect without a restart. */
    public static void reset() { needsBackend.clear(); }

    /** How many paths are currently being served by the backend rather than directly. */
    public static int viaBackend() { return needsBackend.size(); }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}

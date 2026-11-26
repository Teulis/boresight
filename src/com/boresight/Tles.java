package com.boresight;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The element set: fetching it, keeping it, and deciding which satellites are the ones
 * carrying direct-to-cell.
 *
 * Elements are cached on disk and never refetched automatically. That is deliberate —
 * this is the second and last thing in the app that touches the network, it is a 1.7 MB
 * download, and the people using it are often somewhere with no coverage at all. A set
 * stays usable for days; accuracy decays gradually rather than falling off a cliff.
 *
 * Identifying the direct-to-cell satellites: CelesTrak tags them in the object name with
 * a [DTC] suffix, which is authoritative and is used when present. As a cross-check, and
 * as a fallback for anything launched but not yet tagged, they can also be recognised
 * from the orbit itself — they fly a distinctly lower shell than the rest of the
 * constellation, around 340 to 375 km against 450 to 500, at 43 or 53 degrees. Measured
 * against a live set, every tagged satellite fell inside that band.
 */
public final class Tles {

    private static final String URL_STARLINK =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=starlink&FORMAT=tle";
    private static final String FILE = "starlink.tle";

    public static final double DTC_ALT_MIN = 330, DTC_ALT_MAX = 380;

    private final Context ctx;
    private List<Sgp4.Tle> all = new ArrayList<>();
    private List<Sgp4.Tle> dtc = new ArrayList<>();
    private int taggedCount, inferredCount;

    public Tles(Context c) { ctx = c.getApplicationContext(); }

    private File file() { return new File(ctx.getFilesDir(), FILE); }

    public boolean haveElements() { return file().isFile() && file().length() > 1000; }

    /** Age of the cached set in hours, or -1 when there is none. */
    public double ageHours() {
        if (!haveElements()) return -1;
        return (System.currentTimeMillis() - file().lastModified()) / 3600000.0;
    }

    public List<Sgp4.Tle> all() { return all; }
    public List<Sgp4.Tle> directToCell() { return dtc; }
    public int tagged() { return taggedCount; }
    public int inferred() { return inferredCount; }

    /**
     * How much to trust a set of this age. SGP4 drift on a low orbit is roughly a
     * kilometre or two per day, which matters for a pass time but not for "which
     * satellite is that".
     */
    public String freshness() {
        double h = ageHours();
        if (h < 0) return "no elements yet";
        if (h < 24) return String.format(java.util.Locale.US, "%.0f h old, good", h);
        if (h < 72) return String.format(java.util.Locale.US, "%.0f h old, still fine", h);
        if (h < 168) return String.format(java.util.Locale.US, "%.0f days old, drifting",
                h / 24);
        return String.format(java.util.Locale.US,
                "%.0f days old — pass times will be off", h / 24);
    }

    // ------------------------------------------------------------------ loading

    /**
     * Where a set can be dropped by hand. Adb-writable and survives the app being
     * offline, which matters because CelesTrak rate limits and because the people
     * using this are often somewhere with no connection at all.
     */
    public File importPath() {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, FILE);
    }

    /** Copy a hand-placed set into the app's own storage. */
    public boolean importExternal() {
        File src = importPath();
        if (src == null || !src.isFile() || src.length() < 1000) return false;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            FileOutputStream out = new FileOutputStream(file());
            byte[] buf = new byte[32768];
            int n;
            try {
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Parse whatever is cached, importing a hand-placed set first if there is one. */
    public boolean load() {
        if (!haveElements()) importExternal();
        all = new ArrayList<>();
        dtc = new ArrayList<>();
        taggedCount = 0;
        inferredCount = 0;
        if (!haveElements()) return false;

        BufferedReader r = null;
        try {
            r = new BufferedReader(new java.io.FileReader(file()));
            String name, l1, l2;
            while ((name = r.readLine()) != null) {
                l1 = r.readLine();
                l2 = r.readLine();
                if (l1 == null || l2 == null) break;
                Sgp4.Tle t = Sgp4.parse(name, l1, l2);
                if (t == null || t.deepSpace()) continue;
                all.add(t);

                boolean byTag = t.name.contains("[DTC]");
                boolean byOrbit = looksDirectToCell(t);
                if (byTag) taggedCount++;
                if (byOrbit && !byTag) inferredCount++;
                if (byTag) dtc.add(t);
            }
            // Only fall back on the orbital test if the tag has vanished entirely,
            // which would mean the naming changed upstream.
            if (taggedCount == 0) {
                for (Sgp4.Tle t : all) if (looksDirectToCell(t)) dtc.add(t);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /** The orbit-shape test, independent of naming. */
    public static boolean looksDirectToCell(Sgp4.Tle t) {
        double alt = (t.perigeeKm() + t.apogeeKm()) / 2.0;
        if (alt < DTC_ALT_MIN || alt > DTC_ALT_MAX) return false;
        double inc = Math.toDegrees(t.inclo);
        return Math.abs(inc - 43) < 2.0 || Math.abs(inc - 53) < 2.0;
    }

    // ------------------------------------------------------------------ fetching

    public interface Progress { void onProgress(String message); }

    /**
     * Download a fresh set. Always user-initiated; never called on a timer.
     * Writes to a temporary file first so a failed download cannot destroy a good
     * cached set — which matters when the next chance to retry might be days away.
     */
    public boolean fetch(Progress p) {
        HttpURLConnection conn = null;
        File tmp = new File(ctx.getFilesDir(), FILE + ".part");
        try {
            if (p != null) p.onProgress("contacting celestrak.org");
            URL u = new URL(URL_STARLINK);
            conn = (HttpURLConnection) u.openConnection();
            // Generic identification, as CelesTrak asks for. Nothing about the user.
            conn.setRequestProperty("User-Agent", "Boresight/2.0 (satellite tracking)");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code != 200) {
                // 503 is CelesTrak's rate limiter rather than an outage; it clears on
                // its own, and there is a local route in the meantime.
                String why = code == 503
                        ? "CelesTrak is rate limiting or busy (503). Wait a few minutes, "
                          + "or drop a TLE file in the import folder."
                        : "server returned " + code;
                if (p != null) p.onProgress(why);
                return false;
            }

            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[32768];
            long total = 0;
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (p != null && total % (256 * 1024) < 32768) {
                        p.onProgress("downloaded " + (total / 1024) + " KB");
                    }
                }
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }

            if (total < 1000) {
                if (p != null) p.onProgress("response too short to be an element set");
                tmp.delete();
                return false;
            }
            File dest = file();
            dest.delete();
            if (!tmp.renameTo(dest)) {
                if (p != null) p.onProgress("could not replace the cached set");
                return false;
            }
            if (p != null) p.onProgress("stored " + (total / 1024) + " KB");
            return true;
        } catch (Exception e) {
            if (p != null) p.onProgress("failed: " + e.getMessage());
            tmp.delete();
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    public void forget() {
        file().delete();
        all = new ArrayList<>();
        dtc = new ArrayList<>();
    }

    // ------------------------------------------------------------------ overhead

    public static final class Sighting {
        public Sgp4.Tle tle;
        public Geo.Look look;
        public Geo.Geodetic subPoint;

        public String shortName() {
            String n = tle.name.replace("[DTC]", "").trim();
            return n.isEmpty() ? String.valueOf(tle.satnum) : n;
        }
    }

    /**
     * Everything above a minimum elevation right now, strongest geometry first.
     * Propagating the whole set costs about a hundredth of a second, so there is no
     * need to narrow it down beforehand.
     */
    public List<Sighting> overhead(double jd, double lat, double lon, double altKm,
                                   double minElevation) {
        List<Sighting> out = new ArrayList<>();
        Geo.Vec obs = Geo.geodeticToEcef(lat, lon, altKm);
        for (Sgp4.Tle t : dtc) {
            Sgp4 s = new Sgp4(t);
            if (!s.valid()) continue;
            Sgp4.State st = s.at(jd);
            if (!st.ok) continue;
            Geo.Ecef e = Geo.temeToEcef(st, jd);
            Geo.Look l = Geo.look(obs, lat, lon, e);
            if (l.elevation < minElevation) continue;
            Sighting g = new Sighting();
            g.tle = t;
            g.look = l;
            g.subPoint = Geo.ecefToGeodetic(e.r);
            out.add(g);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Sighting>() {
            @Override public int compare(Sighting a, Sighting b) {
                return Double.compare(b.look.elevation, a.look.elevation);
            }
        });
        return out;
    }
}

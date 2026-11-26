package com.boresight;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Known camera positions, from OpenStreetMap.
 *
 * Why this exists: detecting a camera from its own radio emissions is a race you mostly
 * lose. The window is a few seconds wide at road speed, the platform throttles Wi-Fi
 * scanning to roughly one sample every 2,000 feet, newer firmware has moved to hidden
 * networks and locally-administered MACs, and a camera with its radios quiet is simply
 * invisible. A drive past thirty cameras returning nothing is the expected outcome of
 * that arithmetic, not evidence of a broken scanner.
 *
 * Other people have already stood next to these cameras and written down where they are.
 * OpenStreetMap carries them tagged {@code man_made=surveillance} +
 * {@code surveillance:type=ALPR}, usually with the direction the camera faces, often with
 * the manufacturer and the operator. That is a far better primary source than a beacon,
 * and the radio detector becomes what it should always have been: the thing that finds
 * what the map does not already know about.
 *
 * Fetched through the Overpass API, which needs no key. Two things are worth knowing:
 * Overpass rejects requests without a User-Agent, and the public instance is frequently
 * overloaded, so there is a mirror list and a clear message rather than a silent failure.
 *
 * Licensing: OSM data is ODbL 1.0, "(c) OpenStreetMap contributors". That attribution is
 * shown wherever this data is displayed, and is in the credits screen.
 *
 * A pin is a lead, not a confirmed camera, and it can be stale or simply wrong. The UI
 * says so; this class does not pretend otherwise.
 */
public final class Alpr {

    /**
     * Public Overpass instances, tried in order. The .de instance is the reference one
     * and has the best data freshness but is often at capacity, returning 504 or 502;
     * the others are mirrors of the same database.
     */
    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
    };

    /**
     * A generic client name. Overpass asks automated clients to identify themselves, and
     * it gets the app and nothing else: no device identifier, no account, no location
     * beyond the bounding box the query itself needs.
     */
    private static final String UA = "boresight-alpr-layer/1.0";

    private Alpr() { }

    // ------------------------------------------------------------------ model

    public static final class Cam {
        public long osm;
        public double lat, lon;
        /** Compass degrees the camera faces, or -1 when the tag is absent. */
        public int dir = -1;
        public String maker = "", operator = "", mount = "", zone = "";
        /** Filled in by {@link #near}: metres from the query point. */
        public double metres;
        /** Filled in by {@link #near}: compass bearing from the query point. */
        public double bearing;

        public String title() {
            if (maker != null && !maker.isEmpty()) return maker;
            return "ALPR camera";
        }

        public String subtitle() {
            StringBuilder b = new StringBuilder();
            if (operator != null && !operator.isEmpty()) b.append(operator);
            if (zone != null && !zone.isEmpty()) {
                if (b.length() > 0) b.append(" · ");
                b.append(zone);
            }
            if (dir >= 0) {
                if (b.length() > 0) b.append(" · ");
                b.append("faces ").append(compass(dir));
            }
            return b.toString();
        }
    }

    public static final class Fetch {
        public boolean ok;
        public int found, added, updated;
        public String error = "", endpoint = "";
    }

    // ------------------------------------------------------------------ fetching

    /**
     * Pull every ALPR node in a bounding box and merge it into the local table.
     *
     * Keyed on the OSM node id, so re-running over the same area updates rather than
     * duplicates — a camera that was moved or retagged ends up correct instead of
     * appearing twice a few metres apart.
     */
    public static Fetch fetch(Context c, double south, double west,
                              double north, double east) {
        Fetch f = new Fetch();

        // Overpass charges by area and the public instances are shared. Refuse a query
        // big enough to be rude rather than letting it time out at the far end.
        double degs = Math.abs(north - south) * Math.abs(east - west);
        if (degs > 4.0) {
            f.error = "That area is too large to ask a public server for. "
                    + "Try a smaller radius.";
            return f;
        }

        String q = "[out:json][timeout:60];"
                 + "node[\"surveillance:type\"=\"ALPR\"]"
                 + String.format(Locale.US, "(%.6f,%.6f,%.6f,%.6f);", south, west, north, east)
                 + "out body;";

        String body = null;
        StringBuilder problems = new StringBuilder();
        for (String ep : ENDPOINTS) {
            try {
                body = post(ep, q);
                f.endpoint = ep;
                break;
            } catch (Throwable t) {
                if (problems.length() > 0) problems.append("\n");
                problems.append(host(ep)).append(": ").append(t.getMessage());
            }
        }
        if (body == null) {
            f.error = "No Overpass mirror answered.\n\n" + problems
                    + "\n\nThese are free, shared servers and are often at capacity. "
                    + "Waiting a minute usually works.";
            return f;
        }

        try {
            JSONArray els = new JSONObject(body).optJSONArray("elements");
            if (els == null) { f.error = "The server replied without any elements."; return f; }
            f.found = els.length();

            SQLiteDatabase db = Store.of(c).db();
            long now = System.currentTimeMillis();
            db.beginTransaction();
            try {
                for (int i = 0; i < els.length(); i++) {
                    JSONObject e = els.getJSONObject(i);
                    if (!e.has("lat") || !e.has("lon")) continue;
                    long id = e.optLong("id");
                    JSONObject t = e.optJSONObject("tags");

                    ContentValues v = new ContentValues();
                    v.put("osm", id);
                    v.put("lat", e.optDouble("lat"));
                    v.put("lon", e.optDouble("lon"));
                    v.put("dir", direction(t));
                    v.put("maker", tag(t, "manufacturer", "brand"));
                    v.put("operator", tag(t, "operator", "network"));
                    v.put("mount", tag(t, "camera:mount", "support"));
                    v.put("zone", tag(t, "surveillance:zone", "surveillance"));
                    v.put("fetched", now);

                    boolean existed = exists(db, id);
                    db.insertWithOnConflict("alpr", null, v,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    if (existed) f.updated++; else f.added++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            f.ok = true;
        } catch (Throwable t) {
            f.error = "Could not read the reply: " + t.getMessage();
        }
        return f;
    }

    /** Convenience: a square box of the given radius around a point. */
    public static Fetch fetchAround(Context c, double lat, double lon, double km) {
        double dLat = km / 111.0;
        double dLon = km / (111.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        return fetch(c, lat - dLat, lon - dLon, lat + dLat, lon + dLon);
    }

    // ------------------------------------------------------------------ auto load

    /** Radius fetched each time the drive leaves the loaded area. */
    private static final double AUTO_KM = 25;
    /**
     * Re-fetch once the drive gets within this far of the edge, so the next stretch of
     * road is already loaded rather than loading as you reach it.
     */
    private static final double AUTO_MARGIN_KM = 9;
    /** Overpass is a free shared service. Never ask more often than this. */
    private static final long AUTO_MIN_GAP_MS = 4 * 60_000;

    /**
     * Keep the camera layer loaded as the drive moves.
     *
     * Without this the layer silently stops being useful the moment you leave the area
     * you last tapped "load" in — the map still shows cameras behind you, nothing says
     * the road ahead is simply unknown, and the alerts go quiet for the most ordinary
     * reason imaginable. The failure is invisible, which is the kind worth automating
     * away.
     *
     * Deliberately conservative about asking: a public Overpass instance is a shared
     * resource, and a survey app that hammers it on every GPS tick deserves to be
     * blocked. One request per area, at most one every few minutes.
     *
     * @return true when a fetch actually ran
     */
    public static boolean autoLoad(Context c, double lat, double lon) {
        if (!Prefs.bool(c, Prefs.ALPR_AUTO, true)) return false;

        long last = Prefs.get(c).getLong(Prefs.ALPR_SEEN_AT, 0);
        if (System.currentTimeMillis() - last < AUTO_MIN_GAP_MS) return false;

        float seenLat = Prefs.real(c, Prefs.ALPR_SEEN_LAT, Float.NaN);
        float seenLon = Prefs.real(c, Prefs.ALPR_SEEN_LON, Float.NaN);
        if (!Float.isNaN(seenLat) && !Float.isNaN(seenLon)) {
            double moved = Locate.haversineM(lat, lon, seenLat, seenLon);
            if (moved < (AUTO_KM - AUTO_MARGIN_KM) * 1000) return false;
        }

        Fetch f = fetchAround(c, lat, lon, (int) AUTO_KM);
        // Record the attempt either way. A failed fetch that is retried every tick is
        // worse than a gap in the layer, and the public instances fail often.
        Prefs.get(c).edit()
                .putFloat(Prefs.ALPR_SEEN_LAT, (float) lat)
                .putFloat(Prefs.ALPR_SEEN_LON, (float) lon)
                .putLong(Prefs.ALPR_SEEN_AT, System.currentTimeMillis())
                .apply();
        return f.ok;
    }

    /** How far the drive is from the centre of the last load, in metres. */
    public static double metresFromLoaded(Context c, double lat, double lon) {
        float seenLat = Prefs.real(c, Prefs.ALPR_SEEN_LAT, Float.NaN);
        float seenLon = Prefs.real(c, Prefs.ALPR_SEEN_LON, Float.NaN);
        if (Float.isNaN(seenLat) || Float.isNaN(seenLon)) return Double.NaN;
        return Locate.haversineM(lat, lon, seenLat, seenLon);
    }

    /** True when we are outside the area that was actually fetched. */
    public static boolean outsideLoadedArea(Context c, double lat, double lon) {
        double d = metresFromLoaded(c, lat, lon);
        return !Double.isNaN(d) && d > AUTO_KM * 1000;
    }

    private static boolean exists(SQLiteDatabase db, long osm) {
        Cursor c = db.rawQuery("SELECT 1 FROM alpr WHERE osm=?",
                new String[]{String.valueOf(osm)});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    private static String post(String endpoint, String query) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            h.setRequestMethod("POST");
            h.setConnectTimeout(15000);
            h.setReadTimeout(90000);
            h.setDoOutput(true);
            h.setRequestProperty("User-Agent", UA);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = h.getOutputStream();
            os.write(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).getBytes("UTF-8"));
            os.close();

            int code = h.getResponseCode();
            if (code != 200) {
                throw new Exception(code == 429 ? "rate limited"
                                  : code == 504 ? "server busy (504)"
                                  : code == 502 ? "server busy (502)"
                                  : "HTTP " + code);
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(h.getInputStream(), "UTF-8"), 65536);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            h.disconnect();
        }
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Throwable t) { return url; }
    }

    private static String tag(JSONObject t, String... keys) {
        if (t == null) return "";
        for (String k : keys) {
            String v = t.optString(k, "");
            if (!v.isEmpty() && !"yes".equals(v)) return v;
        }
        return "";
    }

    private static int direction(JSONObject t) {
        if (t == null) return -1;
        for (String k : new String[]{"direction", "camera:direction"}) {
            String v = t.optString(k, "");
            if (v.isEmpty()) continue;
            try { return ((int) Double.parseDouble(v) % 360 + 360) % 360; }
            catch (Throwable ignored) { }
            int c = fromCompass(v);
            if (c >= 0) return c;
        }
        return -1;
    }

    private static final String[] POINTS =
            {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
             "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    private static int fromCompass(String s) {
        String u = s.trim().toUpperCase(Locale.US);
        for (int i = 0; i < POINTS.length; i++) {
            if (POINTS[i].equals(u)) return i * 360 / POINTS.length;
        }
        return -1;
    }

    public static String compass(double deg) {
        int i = (int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16;
        return POINTS[i];
    }

    // ------------------------------------------------------------------ queries

    public static int count(Context c) {
        Cursor q = Store.of(c).db().rawQuery("SELECT COUNT(*) FROM alpr", null);
        try { return q.moveToFirst() ? q.getInt(0) : 0; } finally { q.close(); }
    }

    public static long lastFetched(Context c) {
        Cursor q = Store.of(c).db().rawQuery("SELECT MAX(fetched) FROM alpr", null);
        try { return q.moveToFirst() ? q.getLong(0) : 0; } finally { q.close(); }
    }

    public static void clear(Context c) {
        Store.of(c).db().delete("alpr", null, null);
    }

    /**
     * Cameras within a radius, nearest first.
     *
     * Pre-filtered with a bounding box so the index does the work, then measured
     * properly — comparing degrees directly would stretch distances east-west by a
     * factor of about 1.3 at Colorado's latitude and quietly mis-order the results.
     */
    public static List<Cam> near(Context c, double lat, double lon, double metres,
                                 int limit) {
        List<Cam> out = new ArrayList<>();
        double dLat = metres / 111_320.0;
        double dLon = metres / (111_320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));

        Cursor q = Store.of(c).db().rawQuery(
                "SELECT osm, lat, lon, dir, maker, operator, mount, zone FROM alpr "
              + "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                new String[]{
                        String.valueOf(lat - dLat), String.valueOf(lat + dLat),
                        String.valueOf(lon - dLon), String.valueOf(lon + dLon)});
        try {
            float[] r = new float[2];
            while (q.moveToNext()) {
                Cam m = new Cam();
                m.osm = q.getLong(0);
                m.lat = q.getDouble(1);
                m.lon = q.getDouble(2);
                m.dir = q.getInt(3);
                m.maker = q.getString(4);
                m.operator = q.getString(5);
                m.mount = q.getString(6);
                m.zone = q.getString(7);
                android.location.Location.distanceBetween(lat, lon, m.lat, m.lon, r);
                if (r[0] > metres) continue;
                m.metres = r[0];
                m.bearing = ((r[1] % 360) + 360) % 360;
                out.add(m);
            }
        } finally { q.close(); }

        java.util.Collections.sort(out, new java.util.Comparator<Cam>() {
            @Override public int compare(Cam a, Cam b) {
                return Double.compare(a.metres, b.metres);
            }
        });
        if (limit > 0 && out.size() > limit) return out.subList(0, limit);
        return out;
    }

    /**
     * Is this camera ahead of us rather than behind?
     *
     * Only meaningful while actually moving: a GPS bearing standing still is noise, so
     * the caller passes the speed and gets false below walking pace rather than a
     * confident answer built on nothing.
     */
    public static boolean ahead(Cam m, float travelBearing, float speedMps) {
        if (speedMps < 1.5f) return false;
        double delta = Math.abs(((m.bearing - travelBearing + 540) % 360) - 180);
        return delta <= 60;
    }

    /** Metres as a driver would read them. */
    public static String distance(double metres, boolean imperial) {
        if (imperial) {
            double ft = metres * 3.28084;
            if (ft < 1000) return Math.round(ft / 10) * 10 + " ft";
            return String.format(Locale.US, "%.1f mi", metres / 1609.34);
        }
        if (metres < 1000) return Math.round(metres / 10) * 10 + " m";
        return String.format(Locale.US, "%.1f km", metres / 1000.0);
    }
}

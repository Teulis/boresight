package com.boresight;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * On-device survey log. One row per sighting: what was measured, where we stood when
 * we measured it, and when. Nothing is uploaded; export is an explicit user action.
 */
public final class Store extends SQLiteOpenHelper {

    private static final String DB = "survey.db";
    private static final int VERSION = 4;

    public static final String KIND_CELL  = "cell";
    public static final String KIND_WIFI  = "wifi";
    public static final String KIND_BLE   = "ble";
    public static final String KIND_FLOCK = "flock";

    private static Store instance;

    public static synchronized Store of(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private final Context app;

    private Store(Context c) {
        super(c, DB, null, VERSION);
        app = c;
    }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE sessions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "started INTEGER, ended INTEGER, label TEXT, points INTEGER DEFAULT 0)");

        d.execSQL("CREATE TABLE obs ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "session INTEGER, ts INTEGER, kind TEXT,"
                + "lat REAL, lon REAL, acc REAL, alt REAL, speed REAL, bearing REAL,"
                + "tech TEXT, carrier TEXT, mcc TEXT, mnc TEXT,"
                + "ident TEXT, pci INTEGER, tac INTEGER, arfcn INTEGER, band INTEGER,"
                + "dbm INTEGER, qual INTEGER, rsrq INTEGER, rssi INTEGER, ta INTEGER,"
                + "cqi INTEGER, bw INTEGER, age INTEGER, ntn INTEGER,"
                + "cc INTEGER, ccbw INTEGER,"
                + "freq REAL, serving INTEGER DEFAULT 0,"
                + "name TEXT, mac TEXT, extra TEXT,"
                // Where this row was born. See createIdentity.
                + "dev TEXT, seq INTEGER)");
        d.execSQL("CREATE INDEX obs_ts ON obs(ts)");
        d.execSQL("CREATE INDEX obs_kind ON obs(kind)");
        d.execSQL("CREATE INDEX obs_ident ON obs(ident)");
        d.execSQL("CREATE INDEX obs_session ON obs(session)");
        d.execSQL("CREATE INDEX obs_pos ON obs(lat, lon)");

        d.execSQL("CREATE TABLE hits ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER, lat REAL, lon REAL, acc REAL,"
                + "rule TEXT, vendor TEXT, name TEXT, mac TEXT, rssi INTEGER,"
                + "medium TEXT, confidence INTEGER, note TEXT)");
        d.execSQL("CREATE INDEX hits_ts ON hits(ts)");

        d.execSQL("CREATE TABLE rules ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "label TEXT, vendor TEXT, kind TEXT, pattern TEXT,"
                + "medium TEXT, confidence INTEGER, enabled INTEGER DEFAULT 1,"
                + "source TEXT)");

        createAlpr(d);
        createIdentity(d);
    }

    /**
     * Known camera positions imported from OpenStreetMap. Separate from {@code hits},
     * which are things this phone's own radios saw: these are other people's reports,
     * and conflating the two would let a map pin masquerade as a measurement.
     */
    private static void createAlpr(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS alpr ("
                + "osm INTEGER PRIMARY KEY,"          // OSM node id, so re-import updates
                + "lat REAL, lon REAL, dir INTEGER,"
                + "maker TEXT, operator TEXT, mount TEXT, zone TEXT,"
                + "fetched INTEGER)");
        d.execSQL("CREATE INDEX IF NOT EXISTS alpr_pos ON alpr(lat, lon)");
    }

    /**
     * Row identity, and the indices that make merging cheap.
     *
     * The survey is becoming an append-only log measured in millions of rows, synced
     * between two phones and a PC. At that size the old duplicate check — a SELECT on
     * (ts, kind, ident, mac) before every INSERT — is the whole cost of an import: two
     * statements per row, about 2 ms each, which is four and a half hours for eight
     * million rows.
     *
     * A row now carries where it was born: the originating device and a counter that
     * only ever goes up on that device. That pair is exact, it never collides between
     * devices, and a UNIQUE index on it turns the whole duplicate problem into
     * INSERT OR IGNORE — one statement, resolved inside SQLite.
     *
     * The index is partial because rows imported from older bundles have no identity
     * to give them; a plain UNIQUE would collapse every one of them into a single row
     * on NULL. Those keep the old check, which the companion index makes a seek rather
     * than a scan over everything sharing a timestamp.
     */
    private static void createIdentity(SQLiteDatabase d) {
        // Cheap on an existing database: the index is partial, and every row that
        // predates identity has dev NULL, so it starts out almost empty.
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS obs_uid "
                + "ON obs(dev, seq) WHERE dev IS NOT NULL");
        d.execSQL("CREATE INDEX IF NOT EXISTS hits_dedup ON hits(ts, mac)");

        // How far each sync destination has been brought up to date. Keyed by an opaque
        // destination name so a folder, a server and a file export can each have their
        // own high-water mark without knowing about one another.
        d.execSQL("CREATE TABLE IF NOT EXISTS sync_marks ("
                + "dest TEXT PRIMARY KEY, seq INTEGER NOT NULL DEFAULT 0,"
                + "at INTEGER, note TEXT)");

        // Monotonic per-device counter. A table rather than SharedPreferences so it
        // moves inside the same transaction as the rows it numbers: a crash between
        // writing a row and bumping the counter would otherwise reuse a number, and a
        // reused number is a row that silently never syncs.
        d.execSQL("CREATE TABLE IF NOT EXISTS counters ("
                + "name TEXT PRIMARY KEY, value INTEGER NOT NULL DEFAULT 0)");
    }

    /**
     * Migrate, without betting the database on it finishing quickly.
     *
     * A failed onUpgrade is not a bad migration, it is a bricked app: SQLiteOpenHelper
     * rolls the whole thing back, leaves the version where it was, and tries again on
     * every single open. On a phone carrying a long survey that is the difference
     * between an update and a device that will not start.
     *
     * So: each step stands alone and a failure in one does not abandon the rest, and
     * nothing expensive runs here. Building a four-column index across a survey of
     * hundreds of thousands of rows belongs in the background, not between the user
     * tapping the icon and the first frame.
     */
    @Override public void onUpgrade(SQLiteDatabase d, int from, int to) {
        if (from < 2) step(d, "alpr table", null);
        if (from < 3) {
            step(d, "add dev", "ALTER TABLE obs ADD COLUMN dev TEXT");
            step(d, "add seq", "ALTER TABLE obs ADD COLUMN seq INTEGER");
            try { createIdentity(d); } catch (Throwable t) { warn("identity", t); }
        }
        if (from < 4) {
            // RSRQ was measured and displayed but never recorded, so every survey taken
            // before this threw away one of the two numbers that decide whether a cell
            // is usable. Rows written before now keep a null here, which reads as "not
            // measured" rather than as a value — the distinction the rest of the app
            // already relies on.
            step(d, "add rsrq", "ALTER TABLE obs ADD COLUMN rsrq INTEGER");
            // Measured on every sweep already and thrown away: RSSI, CQI and the
            // channel bandwidth. Every comparable tool logs them.
            step(d, "add rssi", "ALTER TABLE obs ADD COLUMN rssi INTEGER");
            step(d, "add cqi", "ALTER TABLE obs ADD COLUMN cqi INTEGER");
            step(d, "add bw", "ALTER TABLE obs ADD COLUMN bw INTEGER");
            // How stale the reading was when it was taken. The platform serves cell
            // info from a cache and does not promise a refresh, so without this a
            // sighting cannot be told from one measured seconds earlier.
            step(d, "add age", "ALTER TABLE obs ADD COLUMN age INTEGER");
            // Serving cell was a satellite. T-Satellite is LTE band 25, so a survey
            // that does not ask records a satellite pass as a tower that is not there.
            step(d, "add ntn", "ALTER TABLE obs ADD COLUMN ntn INTEGER");
            // Component carriers at the moment of the sighting: how many, and how much
            // spectrum in total. Only ever populated with a privileged backend attached.
            step(d, "add cc", "ALTER TABLE obs ADD COLUMN cc INTEGER");
            step(d, "add ccbw", "ALTER TABLE obs ADD COLUMN ccbw INTEGER");
        }
    }

    private void step(SQLiteDatabase d, String what, String sql) {
        try {
            if (sql == null) createAlpr(d);
            else d.execSQL(sql);
        } catch (Throwable t) {
            // A column that already exists throws, and that is fine — it means a
            // previous attempt got this far before failing somewhere later.
            warn(what, t);
        }
    }

    private static void warn(String what, Throwable t) {
        android.util.Log.w("boresight", "migration step '" + what + "': " + t);
    }

    /**
     * The duplicate-check index for rows with no identity.
     *
     * Only the legacy import path needs it, and building it can take a while on a
     * large survey, so it is created once in the background rather than during the
     * upgrade. Everything works without it; the old path is just slower until it
     * exists.
     */
    private volatile boolean dedupIndexAsked;

    public void ensureDedupIndexAsync() {
        if (dedupIndexAsked) return;
        dedupIndexAsked = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    getWritableDatabase().execSQL(
                            "CREATE INDEX IF NOT EXISTS obs_dedup "
                          + "ON obs(ts, kind, ident, mac)");
                } catch (Throwable t) {
                    warn("obs_dedup", t);
                }
                scrubImpossibleLevels();
            }
        }, "obs-dedup-index").start();
    }

    /**
     * Forget received levels that were never measurements.
     *
     * Android reports 127 from a BLE scan to mean "RSSI unavailable", and the recorder
     * stored it as a reading until that was fixed. A positive dBm is stronger than
     * anything real, so those rows won every strongest-sighting comparison and
     * dominated the colour scale wherever they landed — a handful of them was enough
     * to distort the desktop viewer's map.
     *
     * New rows are filtered at the source now; this clears the ones already recorded.
     * Nulling the level rather than deleting the row keeps the sighting, which is
     * genuine: the device was there, only the signal strength was never known.
     */
    private void scrubImpossibleLevels() {
        try {
            getWritableDatabase().execSQL("UPDATE obs SET dbm = NULL WHERE dbm >= 0");
        } catch (Throwable t) {
            warn("scrub levels", t);
        }
    }

    // ------------------------------------------------------------- sessions

    public long startSession(String label) {
        ContentValues v = new ContentValues();
        v.put("started", System.currentTimeMillis());
        v.put("label", label);
        return getWritableDatabase().insert("sessions", null, v);
    }

    public void endSession(long id) {
        ContentValues v = new ContentValues();
        v.put("ended", System.currentTimeMillis());
        getWritableDatabase().update("sessions", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void renameSession(long id, String label) {
        ContentValues v = new ContentValues();
        v.put("label", label);
        getWritableDatabase().update("sessions", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** How many observations point at a session. */
    public long sessionRowCount(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM obs WHERE session = ?",
                new String[]{String.valueOf(id)});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    /**
     * Remove imported sessions that hold nothing.
     *
     * Cleans up after a bug that created one per source session on every re-import
     * regardless of whether any row landed. Scoped to imported sessions so a local
     * recording that genuinely captured nothing is left alone.
     */
    public int pruneEmptyImported() {
        return getWritableDatabase().delete("sessions",
                "label LIKE '%(%' AND id NOT IN (SELECT DISTINCT session FROM obs)",
                null);
    }

    public void deleteSession(long id) {
        String[] a = {String.valueOf(id)};
        getWritableDatabase().delete("obs", "session=?", a);
        getWritableDatabase().delete("sessions", "id=?", a);
    }

    public static class Session {
        public long id, started, ended;
        public String label;
        public int points;
    }

    public List<Session> sessions() {
        List<Session> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.id, s.started, s.ended, s.label, "
              + "(SELECT COUNT(*) FROM obs o WHERE o.session = s.id) "
              + "FROM sessions s ORDER BY s.started DESC", null);
        try {
            while (c.moveToNext()) {
                Session s = new Session();
                s.id = c.getLong(0); s.started = c.getLong(1); s.ended = c.getLong(2);
                s.label = c.getString(3); s.points = c.getInt(4);
                out.add(s);
            }
        } finally { c.close(); }
        return out;
    }

    // ------------------------------------------------------------- writing

    public static class Obs {
        public long id, session, ts;
        public String kind = KIND_CELL;
        public double lat = Double.NaN, lon = Double.NaN;
        public float acc, alt, speed, bearing;
        public String tech, carrier, mcc, mnc, ident, name, mac, extra;
        public int pci = Integer.MAX_VALUE, tac = Integer.MAX_VALUE,
                   arfcn = Integer.MAX_VALUE, band = Integer.MAX_VALUE,
                   dbm = Integer.MAX_VALUE, qual = Integer.MAX_VALUE,
                   rsrq = Integer.MAX_VALUE, rssi = Integer.MAX_VALUE,
                   ta = Integer.MAX_VALUE, cqi = Integer.MAX_VALUE,
                   bwKhz = Integer.MAX_VALUE,
                   /** How stale the modem said this reading was, in ms. */
                   ageMs = Integer.MAX_VALUE,
                   /** Component carriers in use, and their total downlink kHz. */
                   cc = Integer.MAX_VALUE, ccBwKhz = Integer.MAX_VALUE;
        /** Serving cell was a satellite, not a tower. */
        public boolean ntn;
        public boolean serving;
        public double dlMhz = Double.NaN;

        /**
         * Where this row was born: the device that measured it and that device's own
         * ever-increasing counter. Together they identify the row across every copy of
         * the survey, which is what makes incremental sync and exact de-duplication
         * possible. Null and 0 until {@link #insert} stamps them.
         */
        public String dev;
        public long seq;

        public boolean located() {
            return !Double.isNaN(lat) && !Double.isNaN(lon) && (lat != 0 || lon != 0);
        }
    }

    private static void opt(ContentValues v, String k, int val) {
        if (val != Integer.MAX_VALUE && val != Integer.MIN_VALUE) v.put(k, val);
    }

    /**
     * Insert a row that came from a bundle, unless it is already here.
     *
     * Importing the same file twice is the expected case rather than a mistake — you
     * pull it to look, drive some more, pull the updated one — so this has to be
     * idempotent or every re-import doubles the survey. Timestamp plus identity is the
     * key: two genuine sightings of the same cell in the same millisecond do not
     * happen, and the exporter writes the clock value straight through.
     *
     * @return true when the row was new
     */
    /** Whether this exact observation is already recorded. See {@link #insertImported}. */
    public boolean hasObs(Obs o) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM obs WHERE ts = ? AND kind = ? "
              + "AND IFNULL(ident,'') = ? AND IFNULL(mac,'') = ? LIMIT 1",
                new String[]{ String.valueOf(o.ts), o.kind,
                              o.ident == null ? "" : o.ident,
                              o.mac == null ? "" : o.mac });
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    public boolean insertImported(Obs o) {
        Cursor c = getWritableDatabase().rawQuery(
                "SELECT 1 FROM obs WHERE ts = ? AND kind = ? "
              + "AND IFNULL(ident,'') = ? AND IFNULL(mac,'') = ? LIMIT 1",
                new String[]{ String.valueOf(o.ts), o.kind,
                              o.ident == null ? "" : o.ident,
                              o.mac == null ? "" : o.mac });
        try { if (c.moveToFirst()) return false; } finally { c.close(); }
        insert(o);
        return true;
    }

    /** The same idea for detector hits, keyed on time and MAC. */
    public boolean insertHitImported(Hit h) {
        Cursor c = getWritableDatabase().rawQuery(
                "SELECT 1 FROM hits WHERE ts = ? AND IFNULL(mac,'') = ? LIMIT 1",
                new String[]{ String.valueOf(h.ts), h.mac == null ? "" : h.mac });
        try { if (c.moveToFirst()) return false; } finally { c.close(); }
        insertHit(h);
        return true;
    }

    // ------------------------------------------------------------- identity

    /** This device's id, cached so every insert is not a preferences read. */
    private String selfDev;

    public String selfDev() {
        if (selfDev == null) selfDev = Sync.deviceId(app);
        return selfDev;
    }

    /**
     * Next number in this device's append-only sequence.
     *
     * Called inside whatever transaction the caller already holds, so the number and
     * the row it labels commit or roll back together.
     */
    public long nextSeq(String dev) {
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("INSERT OR IGNORE INTO counters(name, value) VALUES(?, 0)",
                new Object[]{"seq:" + dev});
        d.execSQL("UPDATE counters SET value = value + 1 WHERE name = ?",
                new Object[]{"seq:" + dev});
        Cursor c = d.rawQuery("SELECT value FROM counters WHERE name = ?",
                new String[]{"seq:" + dev});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    /** Highest sequence number this device has written. */
    public long currentSeq(String dev) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT value FROM counters WHERE name = ?", new String[]{"seq:" + dev});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    /**
     * Rows this device originated after a given point in its own sequence.
     *
     * Only this device's own rows. Anything imported from the other phone is already
     * in that phone's stream, and re-publishing it would make every device echo every
     * other one — the volume would multiply instead of merge.
     */
    public Cursor obsSince(String dev, long afterSeq, int limit) {
        return getReadableDatabase().rawQuery(
                "SELECT seq, session, ts, kind, lat, lon, acc, alt, speed, bearing, "
              + "tech, carrier, mcc, mnc, ident, name, mac, extra, "
              + "pci, tac, arfcn, band, dbm, qual, ta, serving, freq "
              + "FROM obs WHERE dev = ? AND seq > ? ORDER BY seq LIMIT ?",
                new String[]{dev, String.valueOf(afterSeq), String.valueOf(limit)});
    }

    /** How many of this device's rows are still unsent to a destination. */
    public long pendingSince(String dev, long afterSeq) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM obs WHERE dev = ? AND seq > ?",
                new String[]{dev, String.valueOf(afterSeq)});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    /**
     * Rows with no identity at all, left over from before row identity existed.
     *
     * They cannot take part in incremental sync, because there is nothing to count
     * from. Worth being able to say how many there are rather than quietly never
     * sending them.
     */
    public long unidentifiedCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM obs WHERE dev IS NULL", null);
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    /**
     * Give pre-identity rows this device's identity so they can be synced.
     *
     * Only ever run on rows that have none, and only on rows this phone is the home of
     * — adopting another device's rows would publish them twice under two names.
     */
    public int adoptUnidentified(int limit) {
        String dev = selfDev();
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT id FROM obs WHERE dev IS NULL LIMIT ?",
                new String[]{String.valueOf(limit)});
        int n = 0;
        d.beginTransaction();
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                ContentValues v = new ContentValues();
                v.put("dev", dev);
                v.put("seq", nextSeq(dev));
                d.update("obs", v, "id=?", new String[]{String.valueOf(id)});
                n++;
            }
            d.setTransactionSuccessful();
        } finally {
            c.close();
            d.endTransaction();
        }
        return n;
    }

    // ------------------------------------------------------------- watermarks

    /** How far a named destination has been synced. */
    public long mark(String dest) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT seq FROM sync_marks WHERE dest = ?", new String[]{dest});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    public void setMark(String dest, long seq, String note) {
        ContentValues v = new ContentValues();
        v.put("dest", dest);
        v.put("seq", seq);
        v.put("at", System.currentTimeMillis());
        v.put("note", note);
        getWritableDatabase().insertWithOnConflict("sync_marks", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void clearMark(String dest) {
        getWritableDatabase().delete("sync_marks", "dest=?", new String[]{dest});
    }

    public long markAt(String dest) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT at FROM sync_marks WHERE dest = ?", new String[]{dest});
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    public long insert(Obs o) {
        ContentValues v = new ContentValues();
        v.put("session", o.session);
        v.put("ts", o.ts == 0 ? System.currentTimeMillis() : o.ts);
        v.put("kind", o.kind);
        if (o.located()) {
            v.put("lat", o.lat); v.put("lon", o.lon);
            v.put("acc", o.acc); v.put("alt", o.alt);
            v.put("speed", o.speed); v.put("bearing", o.bearing);
        }
        v.put("tech", o.tech); v.put("carrier", o.carrier);
        v.put("mcc", o.mcc); v.put("mnc", o.mnc);
        v.put("ident", o.ident); v.put("name", o.name);
        v.put("mac", o.mac); v.put("extra", o.extra);
        opt(v, "pci", o.pci); opt(v, "tac", o.tac);
        opt(v, "arfcn", o.arfcn); opt(v, "band", o.band);
        opt(v, "dbm", o.dbm); opt(v, "qual", o.qual); opt(v, "ta", o.ta);
        opt(v, "rsrq", o.rsrq); opt(v, "rssi", o.rssi); opt(v, "cqi", o.cqi);
        opt(v, "bw", o.bwKhz); opt(v, "age", o.ageMs);
        opt(v, "cc", o.cc); opt(v, "ccbw", o.ccBwKhz);
        if (o.ntn) v.put("ntn", 1);
        if (!Double.isNaN(o.dlMhz)) v.put("freq", o.dlMhz);
        v.put("serving", o.serving ? 1 : 0);

        // Stamp where this row was born, unless it arrived already carrying one. A row
        // without an identity cannot be synced incrementally or de-duplicated exactly,
        // so every row gets one at the moment it is written and never afterwards.
        if (o.dev != null && o.seq > 0) {
            v.put("dev", o.dev);
            v.put("seq", o.seq);
        } else {
            String dev = selfDev();
            o.dev = dev;
            o.seq = nextSeq(dev);
            v.put("dev", dev);
            v.put("seq", o.seq);
        }
        // OR IGNORE so a row that is already here by identity costs one indexed insert
        // that does nothing, rather than a query followed by an insert.
        return getWritableDatabase().insertWithOnConflict("obs", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Insert a row from a bundle.
     *
     * Rows that carry an identity go straight in and let the unique index decide; only
     * rows from a bundle old enough to have no identity pay for the lookup.
     *
     * @return true when a row was actually written
     */
    public boolean insertFromBundle(Obs o) {
        if (o.dev != null && o.seq > 0) {
            return insert(o) > 0;
        }
        if (hasObs(o)) return false;
        return insert(o) > 0;
    }

    /**
     * Keep the log honest without letting it grow without bound: one row per
     * identity per cell of the coordinate grid, keeping the strongest reading.
     */
    public boolean insertIfNew(Obs o, double gridDeg) {
        if (o.located() && o.ident != null) {
            double glat = Math.floor(o.lat / gridDeg) * gridDeg;
            double glon = Math.floor(o.lon / gridDeg) * gridDeg;
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id, dbm FROM obs WHERE ident=? AND kind=? "
                  + "AND lat>=? AND lat<? AND lon>=? AND lon<? LIMIT 1",
                    new String[]{o.ident, o.kind,
                            String.valueOf(glat), String.valueOf(glat + gridDeg),
                            String.valueOf(glon), String.valueOf(glon + gridDeg)});
            try {
                if (c.moveToNext()) {
                    long id = c.getLong(0);
                    int old = c.isNull(1) ? Integer.MIN_VALUE : c.getInt(1);
                    if (o.dbm != Integer.MAX_VALUE && o.dbm > old) {
                        ContentValues v = new ContentValues();
                        v.put("dbm", o.dbm);
                        v.put("ts", o.ts == 0 ? System.currentTimeMillis() : o.ts);
                        v.put("lat", o.lat); v.put("lon", o.lon); v.put("acc", o.acc);
                        getWritableDatabase().update("obs", v, "id=?",
                                new String[]{String.valueOf(id)});
                    }
                    return false;
                }
            } finally { c.close(); }
        }
        insert(o);
        return true;
    }

    // ------------------------------------------------------------- reading

    private static Obs read(Cursor c) {
        Obs o = new Obs();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.session = c.getLong(c.getColumnIndexOrThrow("session"));
        o.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        o.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        o.lat = dbl(c, "lat"); o.lon = dbl(c, "lon");
        o.acc = flt(c, "acc"); o.alt = flt(c, "alt");
        o.speed = flt(c, "speed"); o.bearing = flt(c, "bearing");
        o.tech = c.getString(c.getColumnIndexOrThrow("tech"));
        o.carrier = c.getString(c.getColumnIndexOrThrow("carrier"));
        o.mcc = c.getString(c.getColumnIndexOrThrow("mcc"));
        o.mnc = c.getString(c.getColumnIndexOrThrow("mnc"));
        o.ident = c.getString(c.getColumnIndexOrThrow("ident"));
        o.name = c.getString(c.getColumnIndexOrThrow("name"));
        o.mac = c.getString(c.getColumnIndexOrThrow("mac"));
        o.extra = c.getString(c.getColumnIndexOrThrow("extra"));
        o.pci = itg(c, "pci"); o.tac = itg(c, "tac");
        o.arfcn = itg(c, "arfcn"); o.band = itg(c, "band");
        o.dbm = itg(c, "dbm"); o.qual = itg(c, "qual"); o.ta = itg(c, "ta");
        // Added in schema 4. A query that selects an explicit column list may not carry
        // them, so these are looked up leniently rather than with OrThrow — an older
        // projection should read as "not measured", not as a crash.
        o.rsrq = itgSoft(c, "rsrq");
        o.rssi = itgSoft(c, "rssi");
        o.cqi = itgSoft(c, "cqi");
        o.bwKhz = itgSoft(c, "bw");
        o.ageMs = itgSoft(c, "age");
        o.ntn = itgSoft(c, "ntn") == 1;
        o.cc = itgSoft(c, "cc");
        o.ccBwKhz = itgSoft(c, "ccbw");
        o.dlMhz = dbl(c, "freq");
        o.serving = c.getInt(c.getColumnIndexOrThrow("serving")) == 1;
        return o;
    }

    private static double dbl(Cursor c, String k) {
        int i = c.getColumnIndexOrThrow(k);
        return c.isNull(i) ? Double.NaN : c.getDouble(i);
    }
    private static float flt(Cursor c, String k) {
        int i = c.getColumnIndexOrThrow(k);
        return c.isNull(i) ? 0f : c.getFloat(i);
    }
    private static int itg(Cursor c, String k) {
        int i = c.getColumnIndexOrThrow(k);
        return c.isNull(i) ? Integer.MAX_VALUE : c.getInt(i);
    }

    /**
     * Like {@link #itg} but tolerant of a column the query did not select.
     *
     * Columns added by a migration are absent from any projection written before them,
     * and a missing column should read as "not measured" rather than throw — the same
     * answer a row written before the migration gives.
     */
    private static int itgSoft(Cursor c, String k) {
        int i = c.getColumnIndex(k);
        return i < 0 || c.isNull(i) ? Integer.MAX_VALUE : c.getInt(i);
    }

    private static String strSoft(Cursor c, String k) {
        int i = c.getColumnIndex(k);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    /**
     * Named radios in the log, most sightings first.
     *
     * The order is the useful part. What dominates a survey by row count is never the
     * interesting thing — it is whatever was in the car, because that was in range at
     * every single point. So the list is sorted worst-offender first and the thing you
     * want to hold back is at the top rather than somewhere in a thousand rows.
     */
    public List<Rider> mostSeen(int limit) {
        List<Rider> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name, COUNT(*) n, MIN(lat), MAX(lat), MIN(lon), MAX(lon) "
              + "FROM obs WHERE name IS NOT NULL AND TRIM(name) <> '' "
              + "AND kind IN (?, ?) GROUP BY name ORDER BY n DESC LIMIT " + limit,
                new String[]{KIND_WIFI, KIND_BLE});
        try {
            while (c.moveToNext()) {
                Rider r = new Rider();
                r.name = c.getString(0);
                r.rows = c.getInt(1);
                r.spanM = c.isNull(2) ? 0
                        : Locate.haversineM(c.getDouble(2), c.getDouble(4),
                                            c.getDouble(3), c.getDouble(5));
                out.add(r);
            }
        } finally { c.close(); }
        return out;
    }

    /** A named radio and how far it moved with the phone. */
    public static class Rider {
        public String name;
        public int rows;
        public double spanM;
    }

    /**
     * Named radios already in the log that moved too far to be fixed.
     *
     * The same geometry the live filter uses, asked of what was already recorded
     * instead of the current sweep — so it works on a survey taken before the filter
     * existed, and on the devices the live filter cannot catch because they rotate
     * their address. Grouping by name rather than by address is the whole point: a
     * phone key seen under four addresses is one name.
     *
     * The span is the bounding box diagonal rather than a path length. Cheap in SQL,
     * and the question is only "could a fixed transmitter be seen across this?", for
     * which a box is a fair answer and an underestimate of the distance driven.
     */
    public List<Rider> ridersSeen(int limit) {
        List<Rider> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name, COUNT(*) n, MIN(lat), MAX(lat), MIN(lon), MAX(lon) "
              + "FROM obs WHERE name IS NOT NULL AND TRIM(name) <> '' "
              + "AND lat IS NOT NULL AND kind IN (?, ?) "
              + "GROUP BY name HAVING n >= 8",
                new String[]{KIND_WIFI, KIND_BLE});
        try {
            while (c.moveToNext()) {
                double lat0 = c.getDouble(2), lat1 = c.getDouble(3);
                double lon0 = c.getDouble(4), lon1 = c.getDouble(5);
                double span = Locate.haversineM(lat0, lon0, lat1, lon1);
                if (span <= 3000) continue;          // the live rule's threshold
                Rider r = new Rider();
                r.name = c.getString(0);
                r.rows = c.getInt(1);
                r.spanM = span;
                out.add(r);
            }
        } finally { c.close(); }
        Collections.sort(out, new Comparator<Rider>() {
            @Override public int compare(Rider a, Rider b) {
                return Double.compare(b.spanM, a.spanM);
            }
        });
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Located sightings for the map, newest first, capped so the view stays quick. */
    public List<Obs> located(String[] kinds, long sinceTs, int limit) {
        StringBuilder w = new StringBuilder("lat IS NOT NULL");
        List<String> args = new ArrayList<>();
        if (kinds != null && kinds.length > 0) {
            w.append(" AND kind IN (");
            for (int i = 0; i < kinds.length; i++) {
                w.append(i > 0 ? ",?" : "?");
                args.add(kinds[i]);
            }
            w.append(')');
        }
        if (sinceTs > 0) { w.append(" AND ts >= ?"); args.add(String.valueOf(sinceTs)); }
        Cursor c = getReadableDatabase().query("obs", null, w.toString(),
                args.toArray(new String[0]), null, null, "ts DESC", String.valueOf(limit));
        List<Obs> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(read(c)); } finally { c.close(); }
        return out;
    }

    /** Readings for one identity over time, oldest first, for the history graph. */
    public List<Obs> historyOf(String ident, int limit) {
        Cursor c = getReadableDatabase().query("obs", null, "ident=?",
                new String[]{ident}, null, null, "ts ASC", String.valueOf(limit));
        List<Obs> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(read(c)); } finally { c.close(); }
        return out;
    }

    public List<Obs> recent(int limit) {
        Cursor c = getReadableDatabase().query("obs", null, null, null, null, null,
                "ts DESC", String.valueOf(limit));
        List<Obs> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(read(c)); } finally { c.close(); }
        return out;
    }

    public List<Obs> inSession(long session, int limit) {
        Cursor c = getReadableDatabase().query("obs", null, "session=?",
                new String[]{String.valueOf(session)}, null, null,
                "ts ASC", String.valueOf(limit));
        List<Obs> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(read(c)); } finally { c.close(); }
        return out;
    }

    /** One row per distinct identity with a summary, for the cell list. */
    public static class Tally {
        public String ident, tech, carrier;
        public int best, count, arfcn, band;
        public long first, last;
    }

    /**
     * Unlike {@link #radios}, this one's bare columns are safe despite three min/max
     * aggregates: tech and carrier do not vary between sightings of the same identity,
     * so it does not matter which row SQLite takes them from. Do not add a bare column
     * here that does vary — lat and lon above are exactly that mistake.
     */
    public List<Tally> tallies(String kind, String orderBy, int limit) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ident, tech, carrier, MAX(dbm), COUNT(*), MIN(ts), MAX(ts), "
              + "MAX(arfcn), MAX(band) FROM obs WHERE kind=? AND ident IS NOT NULL "
              + "GROUP BY ident ORDER BY " + orderBy + " LIMIT " + limit,
                new String[]{kind});
        List<Tally> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Tally t = new Tally();
                t.ident = c.getString(0); t.tech = c.getString(1); t.carrier = c.getString(2);
                t.best = c.isNull(3) ? Integer.MAX_VALUE : c.getInt(3);
                t.count = c.getInt(4); t.first = c.getLong(5); t.last = c.getLong(6);
                t.arfcn = c.isNull(7) ? Integer.MAX_VALUE : c.getInt(7);
                t.band = c.isNull(8) ? Integer.MAX_VALUE : c.getInt(8);
                out.add(t);
            }
        } finally { c.close(); }
        return out;
    }

    /** One Wi-Fi network or Bluetooth device, summarised across every sighting. */
    public static class Radio {
        public String ident, name, mac, kind, extra;
        public int best = Integer.MAX_VALUE, count, arfcn = Integer.MAX_VALUE;
        public long first, last;
        /** Cell-only, and absent for Wi-Fi and BLE. */
        public String tech, carrier;
        public int pci = Integer.MAX_VALUE, band = Integer.MAX_VALUE;
        public double mean = Double.NaN;
        /** Where it was heard strongest — the best guess at where the thing is. */
        public double lat = Double.NaN, lon = Double.NaN;

        public boolean located() {
            return !Double.isNaN(lat) && !Double.isNaN(lon) && (lat != 0 || lon != 0);
        }

        /** What to call it. A hidden network has no name, and should say so. */
        public String label() {
            if (name != null && !name.trim().isEmpty()) return name;
            if (mac != null && !mac.isEmpty()) return "(unnamed)";
            return "(no identity)";
        }
    }

    /**
     * Distinct radios of a kind, strongest first.
     *
     * Built as a join of two grouped queries rather than one, and the reason is a SQLite
     * rule that is easy to get wrong. Bare columns in an aggregate query take their values
     * from the row that produced the extreme ONLY when the query contains exactly one
     * min() or max(). The earlier version of this query had three — MAX(dbm), MIN(ts) and
     * MAX(ts) — so which row the bare lat, lon, name and mac came from was undefined, and
     * the screen that labelled them "where this was heard loudest" was not entitled to.
     *
     * So the totals are computed in one subquery and the strongest sighting is chosen in
     * another that has a single MAX() and nothing else. The position subquery looks only
     * at located rows: the strongest sighting of all is useless as a position if it was
     * recorded without a fix, and the strongest one that does have a position is the
     * closest this survey ever got to the transmitter.
     *
     * @param nameLike optional substring to match against name or MAC; null for all
     */
    public List<Radio> radios(String kind, String nameLike, int limit) {
        StringBuilder where = new StringBuilder(" WHERE kind = ? AND ident IS NOT NULL");
        List<String> args = new ArrayList<>();
        args.add(kind);
        String like = null;
        if (nameLike != null && !nameLike.trim().isEmpty()) {
            where.append(" AND (name LIKE ? OR mac LIKE ?)");
            like = "%" + nameLike.trim() + "%";
            args.add(like);
            args.add(like);
        }

        // The same bindings once per subquery.
        List<String> all = new ArrayList<>(args);
        all.addAll(args);
        all.addAll(args);

        String sql =
            "SELECT g.ident, g.best, g.n, g.first, g.last, g.mean, "
          + "       a.name, a.mac, s.lat, s.lon, a.arfcn, a.extra, "
          + "       a.tech, a.carrier, a.pci, a.band "
          + "FROM (SELECT ident, MAX(dbm) best, COUNT(*) n, MIN(ts) first, MAX(ts) last, "
          + "             AVG(dbm) mean FROM obs" + where + " GROUP BY ident) g "
          // Describing subquery: one MAX and nothing else, so the bare columns are the
          // strongest sighting's own. Not filtered by position, or a radio that was never
          // heard with a fix would lose its name as well as its place.
          + "LEFT JOIN (SELECT ident, MAX(dbm) bd, name, mac, arfcn, extra, "
          + "                  tech, carrier, pci, band FROM obs" + where
          + "           GROUP BY ident) a ON a.ident = g.ident "
          // Position subquery: the strongest sighting that actually had a fix.
          + "LEFT JOIN (SELECT ident, MAX(dbm) bd, lat, lon FROM obs" + where
          + "           AND lat IS NOT NULL GROUP BY ident) s ON s.ident = g.ident "
          + "ORDER BY g.best IS NULL, g.best DESC LIMIT " + limit;

        Cursor c = getReadableDatabase().rawQuery(sql, all.toArray(new String[0]));
        List<Radio> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Radio r = new Radio();
                r.kind = kind;
                r.ident = c.getString(0);
                if (!c.isNull(1)) r.best = c.getInt(1);
                r.count = c.getInt(2);
                r.first = c.getLong(3);
                r.last = c.getLong(4);
                if (!c.isNull(5)) r.mean = c.getDouble(5);
                r.name = c.getString(6);
                r.mac = c.getString(7);
                if (!c.isNull(8)) { r.lat = c.getDouble(8); r.lon = c.getDouble(9); }
                if (!c.isNull(10)) r.arfcn = c.getInt(10);
                r.extra = c.getString(11);
                r.tech = c.getString(12);
                r.carrier = c.getString(13);
                if (!c.isNull(14)) r.pci = c.getInt(14);
                if (!c.isNull(15)) r.band = c.getInt(15);
                out.add(r);
            }
        } finally { c.close(); }
        return out;
    }

    /** Every sighting of one radio, newest first, for the detail view. */
    public List<Obs> sightingsOf(String ident, int limit) {
        Cursor c = getReadableDatabase().query("obs", null, "ident=?",
                new String[]{ident}, null, null, "ts DESC", String.valueOf(limit));
        List<Obs> out = new ArrayList<>();
        try { while (c.moveToNext()) out.add(read(c)); } finally { c.close(); }
        return out;
    }

    /**
     * When a session's sightings actually begin and end, or null when it holds none.
     *
     * The session row's own started and ended are when the recorder ran, which for an
     * imported session is the moment of the import — so a bundle holding ten hours of
     * driving reported a duration of zero. The rows know better.
     */
    public long[] sessionSpan(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MIN(ts), MAX(ts) FROM obs WHERE session = ?",
                new String[]{String.valueOf(id)});
        try {
            if (!c.moveToFirst() || c.isNull(0)) return null;
            return new long[]{c.getLong(0), c.getLong(1)};
        } finally { c.close(); }
    }

    /** What a noise sweep found, or removed. */
    public static class Sweep {
        public int radios, rows;
        /** The names involved, longest-standing first, so the user can see what is going. */
        public List<String> names = new ArrayList<>();
    }

    /**
     * Apply the noise filter to data that was already recorded.
     *
     * The filter normally runs before a row is written, which does nothing for the rows
     * written before a pattern existed — a Flipper Zero riding in the car for a month is
     * already in the log, and adding it to the list does not take it out. This finds those
     * rows by name and, when asked, deletes them.
     *
     * Always callable as a dry run first. This deletes survey data, and a count the user
     * can read beforehand is the difference between a cleanup and a surprise.
     */
    public Sweep noiseSweep(Context ctx, boolean apply) {
        Sweep s = new Sweep();
        SQLiteDatabase d = getWritableDatabase();
        List<String> doomed = new ArrayList<>();

        // Unnamed rows are included now: the user's list can hold an address, and a
        // device that never broadcast a name is still matchable by one.
        Cursor c = d.rawQuery(
                "SELECT ident, name, mac, COUNT(*) FROM obs "
              + "WHERE ident IS NOT NULL AND kind IN (?, ?) GROUP BY ident",
                new String[]{KIND_WIFI, KIND_BLE});
        try {
            while (c.moveToNext()) {
                String ident = c.getString(0);
                String name = c.getString(1);
                String mac = c.getString(2);
                if (!Noise.filtered(ctx, name, mac)) continue;
                s.radios++;
                s.rows += c.getInt(3);
                doomed.add(ident);
                String label = name != null && !name.trim().isEmpty() ? name : mac;
                if (label != null && s.names.size() < 40 && !s.names.contains(label)) {
                    s.names.add(label);
                }
            }
        } finally { c.close(); }

        if (!apply || doomed.isEmpty()) return s;

        // One transaction: a sweep interrupted half way through would leave a radio with
        // some of its sightings deleted, which is worse than either outcome.
        d.beginTransaction();
        try {
            for (String ident : doomed) {
                d.delete("obs", "ident=?", new String[]{ident});
            }
            d.setTransactionSuccessful();
        } finally { d.endTransaction(); }
        return s;
    }

    /** Delete every sighting of one radio. Returns the number of rows removed. */
    public int deleteIdent(String ident) {
        if (ident == null) return 0;
        return getWritableDatabase().delete("obs", "ident=?", new String[]{ident});
    }

    /** How many distinct radios of a kind the survey holds. */
    public int radioCount(String kind) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT ident) FROM obs WHERE kind = ? AND ident IS NOT NULL",
                new String[]{kind});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public int count(String kind) {
        Cursor c = kind == null
                ? getReadableDatabase().rawQuery("SELECT COUNT(*) FROM obs", null)
                : getReadableDatabase().rawQuery("SELECT COUNT(*) FROM obs WHERE kind=?",
                        new String[]{kind});
        try { return c.moveToNext() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public int distinctCount(String kind) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT ident) FROM obs WHERE kind=?", new String[]{kind});
        try { return c.moveToNext() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    // ------------------------------------------------------------- detections

    public static class Hit {
        public long id, ts;
        public double lat = Double.NaN, lon = Double.NaN;
        public float acc;
        public String rule, vendor, name, mac, medium, note;
        public int rssi = Integer.MAX_VALUE, confidence;
    }

    public long insertHit(Hit h) {
        ContentValues v = new ContentValues();
        v.put("ts", h.ts == 0 ? System.currentTimeMillis() : h.ts);
        if (!Double.isNaN(h.lat)) { v.put("lat", h.lat); v.put("lon", h.lon); v.put("acc", h.acc); }
        v.put("rule", h.rule); v.put("vendor", h.vendor); v.put("name", h.name);
        v.put("mac", h.mac); v.put("medium", h.medium); v.put("note", h.note);
        v.put("confidence", h.confidence);
        if (h.rssi != Integer.MAX_VALUE) v.put("rssi", h.rssi);
        return getWritableDatabase().insert("hits", null, v);
    }

    /** True when this MAC was already recorded near here, so we do not spam the log. */
    public boolean hitSeenNear(String mac, double lat, double lon, double gridDeg) {
        if (mac == null) return false;
        if (Double.isNaN(lat)) {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM hits WHERE mac=? LIMIT 1", new String[]{mac});
            try { return c.moveToNext(); } finally { c.close(); }
        }
        double glat = Math.floor(lat / gridDeg) * gridDeg;
        double glon = Math.floor(lon / gridDeg) * gridDeg;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM hits WHERE mac=? AND lat>=? AND lat<? AND lon>=? AND lon<? LIMIT 1",
                new String[]{mac, String.valueOf(glat), String.valueOf(glat + gridDeg),
                        String.valueOf(glon), String.valueOf(glon + gridDeg)});
        try { return c.moveToNext(); } finally { c.close(); }
    }

    public List<Hit> hits(int limit) {
        Cursor c = getReadableDatabase().query("hits", null, null, null, null, null,
                "ts DESC", String.valueOf(limit));
        List<Hit> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Hit h = new Hit();
                h.id = c.getLong(c.getColumnIndexOrThrow("id"));
                h.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
                h.lat = dbl(c, "lat"); h.lon = dbl(c, "lon"); h.acc = flt(c, "acc");
                h.rule = c.getString(c.getColumnIndexOrThrow("rule"));
                h.vendor = c.getString(c.getColumnIndexOrThrow("vendor"));
                h.name = c.getString(c.getColumnIndexOrThrow("name"));
                h.mac = c.getString(c.getColumnIndexOrThrow("mac"));
                h.medium = c.getString(c.getColumnIndexOrThrow("medium"));
                h.note = c.getString(c.getColumnIndexOrThrow("note"));
                h.rssi = itg(c, "rssi");
                h.confidence = c.getInt(c.getColumnIndexOrThrow("confidence"));
                out.add(h);
            }
        } finally { c.close(); }
        return out;
    }

    public int hitCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM hits", null);
        try { return c.moveToNext() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    // ------------------------------------------------------------- rules

    public SQLiteDatabase db() { return getWritableDatabase(); }

    // ------------------------------------------------------------- housekeeping

    public int purgeOlderThan(long cutoffTs) {
        int n = getWritableDatabase().delete("obs", "ts < ?",
                new String[]{String.valueOf(cutoffTs)});
        n += getWritableDatabase().delete("hits", "ts < ?",
                new String[]{String.valueOf(cutoffTs)});
        return n;
    }

    public void wipe() {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("obs", null, null);
        d.delete("hits", null, null);
        d.delete("sessions", null, null);
        d.execSQL("VACUUM");
    }

    public void wipeLocations() {
        ContentValues v = new ContentValues();
        v.putNull("lat"); v.putNull("lon"); v.putNull("acc");
        v.putNull("alt"); v.putNull("speed"); v.putNull("bearing");
        getWritableDatabase().update("obs", v, null, null);

        ContentValues h = new ContentValues();
        h.putNull("lat"); h.putNull("lon"); h.putNull("acc");
        getWritableDatabase().update("hits", h, null, null);
    }
}

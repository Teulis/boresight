package com.boresight;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Taking the survey off this phone and putting it back on another one.
 *
 * Everything here goes through the Storage Access Framework rather than a cloud SDK.
 * That decision is worth recording, because the obvious alternative looks easier and is
 * not: the Drive Android API was withdrawn in 2019, so a direct integration means the
 * Drive REST API, which means an OAuth client tied to a Google Cloud project and to this
 * APK's signing certificate. For an app built with a local debug key that is a setup
 * chore before the first byte moves, and it buys access to exactly one provider.
 *
 * SAF costs nothing and is provider-agnostic. Google Drive publishes a DocumentsProvider
 * (verified present on this device as StorageBackendContentProvider, reachable only
 * through ACTION_OPEN_DOCUMENT and friends), and so do OneDrive, Dropbox and the local
 * filesystem. Picking a destination once gives a persistable grant that survives reboots,
 * and the same code writes to all of them.
 *
 * Two routes, because providers differ in what they allow:
 *
 *  - A *folder* grant (ACTION_OPEN_DOCUMENT_TREE) is what an automatic push needs.
 *    {@link #destination} is null until one is granted.
 *  - A *one-shot save* (ACTION_CREATE_DOCUMENT) and its matching open
 *    (ACTION_OPEN_DOCUMENT).
 *
 * Which providers appear in which of those is up to the providers, and the two lists are
 * not the same. Measured on a Samsung device with Drive signed in on four accounts:
 * Drive appears in the save sheet once per account and in the open sheet, and does not
 * appear in the folder picker at all. So Drive is reachable by save and open, and cannot
 * be an automatic push target; a local or SD folder can.
 *
 * That is why the save and open pair is the primary route on the sync screen rather than
 * the fallback, and why nothing here claims a folder will work before one is granted.
 */
public final class Sync {

    /**
     * Bundle format version, so a future reader can tell what it is looking at.
     *
     * 2 adds per-row identity (dev, seq). A reader that understands 2 can merge by
     * identity, which is one indexed insert per row instead of a lookup followed by an
     * insert — the difference between minutes and hours once the survey is millions of
     * rows. Format 1 bundles still import, just by the slower route.
     */
    private static final int FORMAT = 2;

    public static final String MIME = "application/json";

    private Sync() { }

    // ------------------------------------------------------------------ identity

    /**
     * A stable id for this phone, so bundles from two devices never overwrite each other
     * and a merge can tell where a row came from. Random rather than derived from
     * anything real: a hardware id would be a tracking identifier travelling inside an
     * exported file, which is the opposite of what the privacy settings are for.
     */
    public static String deviceId(Context c) {
        String id = Prefs.string(c, Prefs.DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = Long.toHexString(java.util.UUID.randomUUID().getMostSignificantBits()
                    & 0xFFFFFFFFFFFFL);
            Prefs.get(c).edit().putString(Prefs.DEVICE_ID, id).apply();
        }
        return id;
    }

    public static String deviceName(Context c) {
        String n = Prefs.string(c, Prefs.DEVICE_NAME, null);
        if (n == null || n.trim().isEmpty()) n = android.os.Build.MODEL;
        return n;
    }

    // ------------------------------------------------------------------ destination

    /** The granted folder, or null when none has been chosen or the grant has lapsed. */
    public static Uri destination(Context c) {
        String s = Prefs.string(c, Prefs.SYNC_TREE, null);
        if (s == null || s.isEmpty()) return null;
        Uri u = Uri.parse(s);
        // A grant can be revoked from the system settings, or the provider can be
        // uninstalled. Checking is cheaper than failing later with no explanation.
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    public static String destinationLabel(Context c) {
        Uri u = destination(c);
        if (u == null) return "not set";
        String id = DocumentsContract.getTreeDocumentId(u);
        String auth = u.getAuthority();
        String where = auth == null ? "" : providerName(auth);
        return (where.isEmpty() ? "" : where + " · ") + (id == null ? u.toString() : id);
    }

    /** Human name for the better-known providers; the authority itself otherwise. */
    private static String providerName(String authority) {
        String a = authority.toLowerCase(Locale.US);
        if (a.contains("apps.docs")) return "Google Drive";
        if (a.contains("skydrive") || a.contains("onedrive")) return "OneDrive";
        if (a.contains("dropbox")) return "Dropbox";
        if (a.equals("com.android.externalstorage.documents")) return "This device";
        if (a.equals("com.android.providers.downloads.documents")) return "Downloads";
        return authority;
    }

    public static Intent pickFolderIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                 | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                 | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    public static Intent saveFileIntent(String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType(MIME);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE, name);
        return i;
    }

    public static Intent openFileIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        return i;
    }

    /** Remember a folder the user just granted. */
    public static boolean rememberFolder(Context c, Uri tree) {
        if (tree == null) return false;
        try {
            c.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                  | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Prefs.get(c).edit().putString(Prefs.SYNC_TREE, tree.toString()).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void forgetFolder(Context c) {
        Uri u = destination(c);
        if (u != null) {
            try {
                c.getContentResolver().releasePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) { }
        }
        Prefs.get(c).edit().remove(Prefs.SYNC_TREE).apply();
    }

    // ------------------------------------------------------------------ writing

    public static final class Result {
        public boolean ok;
        public String where = "";
        public int sessions, points, hits;
        public String error;
    }

    public static String bundleName(Context c) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        return "boresight-" + deviceId(c) + "-" + stamp + ".json";
    }

    /**
     * Write every session to the granted folder, creating the file there.
     *
     * Overwriting is avoided on purpose: each push is a new timestamped file, so a bad
     * export can never destroy a good one, and the folder doubles as a history. Pruning
     * is the user's business, not ours.
     */
    public static Result push(Context c, long onlySession) {
        Result r = new Result();
        Uri tree = destination(c);
        if (tree == null) {
            r.error = "No sync folder has been granted.";
            return r;
        }
        try {
            ContentResolver cr = c.getContentResolver();
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            Uri doc = DocumentsContract.createDocument(cr, parent, MIME, bundleName(c));
            if (doc == null) {
                r.error = "The provider refused to create a file in that folder. "
                        + "Some cloud providers grant a folder but will not accept "
                        + "writes into it — use Export once instead.";
                return r;
            }
            try {
                writeBundle(c, cr, doc, onlySession, r);
            } catch (Throwable t) {
                // Created but not finished: remove it so the folder never accumulates
                // files that only look like backups. We own this one, so we can.
                try { DocumentsContract.deleteDocument(cr, doc); } catch (Throwable ignored) { }
                throw t;
            }
            r.where = destinationLabel(c);
            if (r.ok) {
                Prefs.get(c).edit().putLong(Prefs.SYNC_LAST, System.currentTimeMillis()).apply();
            }
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
        }
        return r;
    }

    /** Write to a document the user picked themselves. Always available. */
    public static Result writeTo(Context c, Uri doc, long onlySession) {
        Result r = new Result();
        try {
            writeBundle(c, c.getContentResolver(), doc, onlySession, r);
            r.where = displayName(c, doc);
        } catch (Throwable t) {
            // A throw partway through leaves a truncated file that still looks like a
            // bundle from the outside. Say so, rather than letting it be discovered on
            // the day someone tries to restore from it.
            r.error = t.getMessage() + "\n\nThe file was created and is incomplete — "
                    + "delete it.";
        }
        return r;
    }

    /**
     * The bundle itself: sessions, observations and detector hits, written as a stream
     * rather than assembled in memory. A long survey is tens of thousands of rows, and
     * building that as one String first is how an export turns into an out-of-memory
     * crash on the day it matters.
     */
    private static void writeBundle(Context c, ContentResolver cr, Uri doc,
                                    long onlySession, Result r) throws Exception {
        Store store = Store.of(c);
        OutputStream os = cr.openOutputStream(doc, "wt");
        if (os == null) throw new Exception("could not open the file for writing");
        Writer w = new OutputStreamWriter(os, "UTF-8");
        try {
            w.write("{\n");
            w.write("\"format\":" + FORMAT + ",\n");
            w.write("\"app\":\"boresight\",\n");
            w.write("\"device\":" + JSONObject.quote(deviceId(c)) + ",\n");
            w.write("\"deviceName\":" + JSONObject.quote(deviceName(c)) + ",\n");
            w.write("\"written\":" + System.currentTimeMillis() + ",\n");

            w.write("\"sessions\":[");
            boolean first = true;
            for (Store.Session s : store.sessions()) {
                if (onlySession > 0 && s.id != onlySession) continue;
                if (!first) w.write(",");
                first = false;
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("started", s.started);
                o.put("ended", s.ended);
                o.put("label", s.label == null ? JSONObject.NULL : s.label);
                o.put("points", s.points);
                w.write(o.toString());
                r.sessions++;
            }
            w.write("],\n");

            w.write("\"obs\":[");
            r.points = streamObs(store, w, onlySession);
            w.write("],\n");

            w.write("\"hits\":[");
            first = true;
            for (Store.Hit h : store.hits(100000)) {
                if (!first) w.write(",");
                first = false;
                JSONObject o = new JSONObject();
                o.put("ts", h.ts);
                if (!Double.isNaN(h.lat)) { o.put("lat", h.lat); o.put("lon", h.lon); }
                put(o, "rule", h.rule);
                put(o, "vendor", h.vendor);
                put(o, "name", h.name);
                put(o, "mac", h.mac);
                put(o, "medium", h.medium);
                put(o, "note", h.note);
                if (h.rssi != Integer.MAX_VALUE) o.put("rssi", h.rssi);
                o.put("confidence", h.confidence);
                w.write(o.toString());
                r.hits++;
            }
            w.write("]\n}\n");
            w.flush();
            r.ok = true;
        } finally {
            try { w.close(); } catch (Throwable ignored) { }
        }
    }

    /** Straight off the cursor, a row at a time. */
    private static int streamObs(Store store, Writer w, long onlySession) throws Exception {
        // The downlink frequency is stored in a column called "freq" while the field on
        // Obs is dlMhz. Naming the field here instead of the column threw on every
        // export and truncated the bundle at the opening bracket.
        String sql = "SELECT id, session, ts, kind, lat, lon, acc, alt, speed, bearing, "
                   + "tech, carrier, mcc, mnc, ident, name, mac, extra, "
                   + "pci, tac, arfcn, band, dbm, qual, ta, serving, freq, "
                   + "dev, seq, "
                   // Appended at the end so every existing positional index above
                   // stays put. Schema 4.
                   + "rsrq, rssi, cqi, bw, age, ntn, cc, ccbw FROM obs";
        String[] args = null;
        if (onlySession > 0) { sql += " WHERE session = ?"; args = new String[]{String.valueOf(onlySession)}; }
        sql += " ORDER BY ts";

        Cursor c = store.db().rawQuery(sql, args);
        int n = 0;
        try {
            boolean first = true;
            while (c.moveToNext()) {
                if (!first) w.write(",");
                first = false;
                JSONObject o = new JSONObject();
                o.put("session", c.getLong(1));
                o.put("ts", c.getLong(2));
                put(o, "kind", c.getString(3));
                if (!c.isNull(4)) { o.put("lat", c.getDouble(4)); o.put("lon", c.getDouble(5)); }
                if (!c.isNull(6)) o.put("acc", c.getFloat(6));
                if (!c.isNull(8)) o.put("speed", c.getFloat(8));
                if (!c.isNull(9)) o.put("bearing", c.getFloat(9));
                put(o, "tech", c.getString(10));
                put(o, "carrier", c.getString(11));
                put(o, "mcc", c.getString(12));
                put(o, "mnc", c.getString(13));
                put(o, "ident", c.getString(14));
                put(o, "name", c.getString(15));
                put(o, "mac", c.getString(16));
                put(o, "extra", c.getString(17));
                num(o, "pci", c, 18);
                num(o, "tac", c, 19);
                num(o, "arfcn", c, 20);
                num(o, "band", c, 21);
                num(o, "dbm", c, 22);
                num(o, "qual", c, 23);
                num(o, "ta", c, 24);
                if (!c.isNull(25) && c.getInt(25) != 0) o.put("serving", true);
                if (!c.isNull(26)) o.put("dlMhz", c.getDouble(26));
                // Row identity, so the far end can merge by it rather than guessing
                // from timestamp and name.
                if (!c.isNull(27)) o.put("dev", c.getString(27));
                if (!c.isNull(28)) o.put("seq", c.getLong(28));
                num(o, "rsrq", c, 29);
                num(o, "rssi", c, 30);
                num(o, "cqi",  c, 31);
                num(o, "bw",   c, 32);
                num(o, "age",  c, 33);
                if (!c.isNull(34) && c.getInt(34) != 0) o.put("ntn", true);
                num(o, "cc",   c, 35);
                num(o, "ccbw", c, 36);
                w.write(o.toString());
                n++;
                if ((n & 0xFF) == 0) w.flush();
            }
        } finally { c.close(); }
        return n;
    }

    private static void put(JSONObject o, String k, String v) throws Exception {
        if (v != null && !v.isEmpty()) o.put(k, v);
    }

    private static void num(JSONObject o, String k, Cursor c, int col) throws Exception {
        if (!c.isNull(col)) o.put(k, c.getInt(col));
    }

    // ------------------------------------------------------------------ reading back

    public static final class Incoming {
        public boolean ok;
        public String device = "", deviceName = "";
        public long written;
        public int sessions, points, hits;
        public int addedSessions, addedPoints, addedHits, skipped;
        /** Bundle format the file declares, so a version mismatch is visible. */
        public int format = 1;
        public String error;
    }

    /**
     * Read a bundle written by this or another phone and merge it in.
     *
     * Merge rather than replace, and de-duplicated on the way in, because the expected
     * use is pulling the same file twice — once to look at it, once after a few more
     * drives — and a naive import would double every row. Sessions are re-keyed: the
     * other phone's session 3 is not this phone's session 3, and quietly conflating
     * them would silently corrupt both surveys.
     *
     * @param inspectOnly count what is in the file without writing anything
     */
    public static Incoming pull(Context c, Uri doc, boolean inspectOnly) {
        Incoming in = new Incoming();
        InputStream is = null;
        android.util.JsonReader r = null;
        Store store = Store.of(c);
        android.database.sqlite.SQLiteDatabase db = store.db();
        boolean inTransaction = false;
        // Only matters for bundles whose rows carry no identity, but we do not know
        // which kind this is until the first row — and asking is free after the first
        // time.
        store.ensureDedupIndexAsync();
        try {
            is = c.getContentResolver().openInputStream(doc);
            if (is == null) throw new Exception("could not open that file");
            r = new android.util.JsonReader(
                    new InputStreamReader(is, "UTF-8"));

            java.util.HashMap<Long, Long> remap = new java.util.HashMap<>();
            java.util.HashMap<Long, String> labels = new java.util.HashMap<>();
            boolean headerChecked = false;

            // Sessions this import created, so the empty ones can be removed at the
            // end. A session has to exist before a row can point at it, but whether
            // the row actually lands is now decided inside the insert by the unique
            // index — so an import of nothing new still creates a session per source
            // session first and finds out afterwards. Re-importing the same bundle
            // was adding nine empty sessions every time.
            java.util.List<Long> created = new java.util.ArrayList<>();

            r.beginObject();
            while (r.hasNext()) {
                String key = r.nextName();
                if ("app".equals(key)) {
                    String app = r.nextString();
                    if (!"boresight".equals(app)) {
                        throw new Exception("that is not a Boresight bundle");
                    }
                } else if ("format".equals(key)) {
                    in.format = r.nextInt();
                    // Two phones on two builds is the normal case, not the exception:
                    // the one in the car gets updated when it gets updated. An older
                    // bundle is always safe, because the reader below skips fields it
                    // does not know. A *newer* one is not, since a field that changed
                    // meaning would be imported under the old reading and quietly
                    // corrupt the survey. Refuse rather than guess.
                    if (in.format > FORMAT) {
                        throw new Exception("this bundle is format " + in.format
                                + " and this build only understands " + FORMAT
                                + ".\n\nIt was written by a newer version of Boresight. "
                                + "Update this phone and import it again - importing it "
                                + "now could misread fields that have changed meaning.");
                    }
                } else if ("device".equals(key)) {
                    in.device = r.nextString();
                } else if ("deviceName".equals(key)) {
                    in.deviceName = r.nextString();
                } else if ("written".equals(key)) {
                    in.written = r.nextLong();
                } else if ("sessions".equals(key)) {
                    r.beginArray();
                    while (r.hasNext()) {
                        long id = 0;
                        String label = null;
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if ("id".equals(k)) id = r.nextLong();
                            else if ("label".equals(k)) {
                                if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); }
                                else label = r.nextString();
                            } else r.skipValue();
                        }
                        r.endObject();
                        if (label == null || label.isEmpty()) label = "imported";
                        labels.put(id, label);
                        in.sessions++;
                    }
                    r.endArray();
                } else if ("obs".equals(key)) {
                    headerChecked = true;
                    if (!inspectOnly && !inTransaction) {
                        // One transaction for the whole import. Without it SQLite
                        // commits per row, and a survey of this size spends minutes
                        // doing nothing but fsync.
                        db.beginTransaction();
                        inTransaction = true;
                    }
                    r.beginArray();
                    while (r.hasNext()) {
                        Store.Obs e = new Store.Obs();
                        long oldSession = 0;
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); continue; }
                            if ("session".equals(k))      oldSession = r.nextLong();
                            else if ("ts".equals(k))      e.ts = r.nextLong();
                            else if ("kind".equals(k))    e.kind = r.nextString();
                            else if ("lat".equals(k))     e.lat = r.nextDouble();
                            else if ("lon".equals(k))     e.lon = r.nextDouble();
                            else if ("acc".equals(k))     e.acc = (float) r.nextDouble();
                            else if ("speed".equals(k))   e.speed = (float) r.nextDouble();
                            else if ("bearing".equals(k)) e.bearing = (float) r.nextDouble();
                            else if ("tech".equals(k))    e.tech = r.nextString();
                            else if ("carrier".equals(k)) e.carrier = r.nextString();
                            else if ("mcc".equals(k))     e.mcc = r.nextString();
                            else if ("mnc".equals(k))     e.mnc = r.nextString();
                            else if ("ident".equals(k))   e.ident = r.nextString();
                            else if ("name".equals(k))    e.name = r.nextString();
                            else if ("mac".equals(k))     e.mac = r.nextString();
                            else if ("extra".equals(k))   e.extra = r.nextString();
                            else if ("pci".equals(k))     e.pci = r.nextInt();
                            else if ("tac".equals(k))     e.tac = r.nextInt();
                            else if ("arfcn".equals(k))   e.arfcn = r.nextInt();
                            else if ("band".equals(k))    e.band = r.nextInt();
                            else if ("dbm".equals(k))     e.dbm = r.nextInt();
                            else if ("qual".equals(k))    e.qual = r.nextInt();
                            else if ("ta".equals(k))      e.ta = r.nextInt();
                            else if ("serving".equals(k)) e.serving = r.nextBoolean();
                            else if ("dlMhz".equals(k))   e.dlMhz = r.nextDouble();
                            else if ("dev".equals(k))     e.dev = r.nextString();
                            else if ("seq".equals(k))     e.seq = r.nextLong();
                            else if ("rsrq".equals(k))    e.rsrq = r.nextInt();
                            else if ("rssi".equals(k))    e.rssi = r.nextInt();
                            else if ("cqi".equals(k))     e.cqi = r.nextInt();
                            else if ("bw".equals(k))      e.bwKhz = r.nextInt();
                            else if ("age".equals(k))     e.ageMs = r.nextInt();
                            else if ("ntn".equals(k))     e.ntn = r.nextBoolean();
                            else if ("cc".equals(k))      e.cc = r.nextInt();
                            else if ("ccbw".equals(k))    e.ccBwKhz = r.nextInt();
                            else r.skipValue();
                        }
                        r.endObject();
                        in.points++;
                        if (inspectOnly) continue;
                        if (e.kind == null) e.kind = Store.KIND_CELL;

                        // Duplicate check before the session exists, so a bundle with
                        // nothing new in it creates nothing at all. Rows that carry an
                        // identity are checked by the unique index instead, inside the
                        // insert, which is why this is the slow path and not the only
                        // path.
                        boolean identified = e.dev != null && e.seq > 0;
                        if (!identified && store.hasObs(e)) { in.skipped++; continue; }
                        Long mapped = remap.get(oldSession);
                        if (mapped == null) {
                            String label = labels.get(oldSession);
                            String tag = in.deviceName + " · " + fmt(in.written);
                            mapped = store.startSession(
                                    (label == null ? "imported" : label) + "  (" + tag + ")");
                            store.endSession(mapped);
                            remap.put(oldSession, mapped);
                            created.add(mapped);
                        }
                        e.session = mapped;
                        if (store.insert(e) > 0) in.addedPoints++;
                        else in.skipped++;
                    }
                    r.endArray();
                } else if ("hits".equals(key)) {
                    if (!inspectOnly && !inTransaction) {
                        db.beginTransaction();
                        inTransaction = true;
                    }
                    r.beginArray();
                    while (r.hasNext()) {
                        Store.Hit e = new Store.Hit();
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); continue; }
                            if ("ts".equals(k))             e.ts = r.nextLong();
                            else if ("lat".equals(k))       e.lat = r.nextDouble();
                            else if ("lon".equals(k))       e.lon = r.nextDouble();
                            else if ("rule".equals(k))      e.rule = r.nextString();
                            else if ("vendor".equals(k))    e.vendor = r.nextString();
                            else if ("name".equals(k))      e.name = r.nextString();
                            else if ("mac".equals(k))       e.mac = r.nextString();
                            else if ("medium".equals(k))    e.medium = r.nextString();
                            else if ("note".equals(k))      e.note = r.nextString();
                            else if ("rssi".equals(k))      e.rssi = r.nextInt();
                            else if ("confidence".equals(k)) e.confidence = r.nextInt();
                            else r.skipValue();
                        }
                        r.endObject();
                        in.hits++;
                        if (inspectOnly) continue;
                        if (store.insertHitImported(e)) in.addedHits++; else in.skipped++;
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();

            if (!headerChecked && in.points == 0 && in.sessions == 0 && in.hits == 0) {
                throw new Exception("that file has no survey data in it");
            }

            // Drop the sessions nothing landed in, and count only the ones that kept
            // a row. Otherwise the history fills with empty entries every time the
            // same bundle is looked at twice.
            for (Long id : created) {
                if (store.sessionRowCount(id) == 0) store.deleteSession(id);
                else in.addedSessions++;
            }
            // Also sweep up any left behind by earlier imports that had this bug.
            if (!inspectOnly) store.pruneEmptyImported();

            if (inTransaction) {
                db.setTransactionSuccessful();
            }
            in.ok = true;
        } catch (Throwable t) {
            in.error = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
        } finally {
            // Roll back rather than leave a half-merged survey: setTransactionSuccessful
            // is only reached on the clean path, so a throw partway through undoes
            // everything this import wrote.
            if (inTransaction) {
                try { db.endTransaction(); } catch (Throwable ignored) { }
            }
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
            if (is != null) try { is.close(); } catch (Throwable ignored) { }
        }
        return in;
    }

    /** Bundles already sitting in the granted folder, newest first. */
    public static List<String[]> listRemote(Context c) {
        List<String[]> out = new ArrayList<>();
        Uri tree = destination(c);
        if (tree == null) return out;
        Cursor cur = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            cur = c.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            }, null, null, null);
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(1);
                if (name == null || !name.startsWith("boresight-")) continue;
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                out.add(new String[]{ name, String.valueOf(cur.getLong(2)),
                        fmt(cur.getLong(3)), doc.toString() });
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
        java.util.Collections.sort(out, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return b[0].compareTo(a[0]); }
        });
        return out;
    }

    /**
     * The name a person would recognise, for a document URI.
     *
     * getLastPathSegment on a picked document returns the provider's internal document
     * id — a bare number on Downloads — so a confirmation dialog built from it asks
     * "merge 14783?", which tells the user nothing about what they picked.
     */
    public static String displayName(Context c, Uri doc) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(doc,
                    new String[]{ DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                    null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
        String seg = doc.getLastPathSegment();
        return seg == null ? "selected file" : seg;
    }

    public static String fmt(long ms) {
        if (ms <= 0) return "never";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }
}

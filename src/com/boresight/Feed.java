package com.boresight;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Incremental sync: publish only what is new, in chunks, to a destination that does not
 * much care what it is.
 *
 * The survey outgrew whole-file export. Measured on real data this phone collects about
 * 6,200 rows an hour, so a month of the intended use is around 700,000 rows and 200 MB
 * of JSON, and a year is eight million. Re-exporting all of it on every sync is not a
 * slow version of the right idea, it is the wrong idea: the cost grows with history
 * rather than with what actually happened since last time.
 *
 * So the survey is treated as an append-only log. Every row carries the device that
 * measured it and that device's own ever-increasing counter (see Store.createIdentity).
 * Each destination remembers the highest counter it has received. A push reads only
 * past that mark, writes it as a chunk, and moves the mark. Nothing is ever rewritten,
 * so a chunk that has landed is final and a chunk that failed can simply be sent again.
 *
 * Only this device's own rows are published. Rows that arrived from the other phone
 * belong to that phone's stream; echoing them back would multiply the volume instead of
 * merging it.
 *
 * Format is newline-delimited JSON, gzipped:
 *
 *   line 0   a header object — format, device, range, when
 *   line 1+  one observation per line
 *
 * One object per line rather than one array, because that is what lets both ends stream.
 * A reader never holds more than a line, an appender never rewrites a file, and a
 * truncated chunk loses its last line instead of being unparseable. The JSON array the
 * old bundles used had to be read whole: 12 MB of it cost 101 MB of heap on this phone.
 *
 * Gzip because this data compresses extremely well — the same carrier strings, band
 * numbers and cell identities repeat on every row.
 *
 * {@link Target} is the only thing that knows where chunks go. A folder today; an HTTP
 * endpoint later, with no change to anything above it.
 */
public final class Feed {

    /** Bundle format for chunks. Independent of the old whole-file bundle format. */
    public static final int CHUNK_FORMAT = 1;

    /**
     * Rows per chunk.
     *
     * Small enough that a failed upload is cheap to retry and that OpenCelliD's 2 MB
     * limit is reachable after conversion, large enough that a long drive does not
     * produce thousands of files. 20,000 rows is roughly 1.5 MB gzipped.
     */
    public static final int ROWS_PER_CHUNK = 20_000;

    private Feed() { }

    // ------------------------------------------------------------------ target

    /** Somewhere chunks can be put. Implementations must be safe to retry. */
    public interface Target {
        /** Stable key for the watermark. Changing it re-sends everything. */
        String key();
        String label();
        boolean available(Context c);
        /** Store one chunk. Must throw rather than half-succeed. */
        void put(Context c, String filename, byte[] body) throws Exception;
    }

    /**
     * A granted folder, through the Storage Access Framework.
     *
     * Works with local storage, an SD card, or any cloud provider that offers a folder
     * — which, measured on this device, Drive does not. Drive is reachable by the save
     * sheet but offers no folder, so for Drive the manual export remains the route and
     * this target is for local, SD, or a folder a desktop client syncs.
     */
    public static final class FolderTarget implements Target {
        @Override public String key() { return "folder"; }
        @Override public String label() { return "Sync folder"; }

        @Override public boolean available(Context c) {
            return Sync.destination(c) != null;
        }

        @Override public void put(Context c, String filename, byte[] body) throws Exception {
            Uri tree = Sync.destination(c);
            if (tree == null) throw new Exception("no folder has been granted");
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            Uri doc = DocumentsContract.createDocument(
                    c.getContentResolver(), parent, "application/gzip", filename);
            if (doc == null) throw new Exception("the provider refused to create " + filename);
            OutputStream os = c.getContentResolver().openOutputStream(doc, "wt");
            if (os == null) throw new Exception("could not open " + filename + " for writing");
            try {
                os.write(body);
                os.flush();
            } catch (Throwable t) {
                // Remove the stub rather than leave something that looks like a chunk
                // and is not. A half-written chunk read back later is worse than a gap.
                try { DocumentsContract.deleteDocument(c.getContentResolver(), doc); }
                catch (Throwable ignored) { }
                throw t;
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ push

    public static final class Result {
        public boolean ok;
        public int chunks;
        public long rows;
        public long bytes;
        public long fromSeq, toSeq;
        public long remaining;
        public String error = "";
        public String where = "";
    }

    /** What is waiting to go to a destination. */
    public static long pending(Context c, Target t) {
        Store s = Store.of(c);
        return s.pendingSince(s.selfDev(), s.mark(t.key()));
    }

    /**
     * Send everything new, in chunks, stopping at the first failure.
     *
     * The watermark only moves after a chunk is safely stored, and it moves one chunk
     * at a time. A failure halfway through a long backlog therefore keeps everything
     * already sent and resumes from exactly there — no gap, and nothing sent twice.
     *
     * @param maxChunks 0 for no limit; a small number to bound a single run
     */
    public static Result push(Context c, Target t, int maxChunks) {
        Result r = new Result();
        r.where = t.label();
        Store store = Store.of(c);
        String dev = store.selfDev();

        if (!t.available(c)) {
            r.error = "No destination is set up for " + t.label() + ".";
            return r;
        }

        long mark = store.mark(t.key());
        r.fromSeq = mark;
        r.toSeq = mark;

        try {
            while (maxChunks <= 0 || r.chunks < maxChunks) {
                Chunk ch = build(c, store, dev, mark);
                if (ch == null || ch.rows == 0) break;

                t.put(c, ch.filename, ch.body);

                mark = ch.lastSeq;
                store.setMark(t.key(), mark, t.label());
                r.chunks++;
                r.rows += ch.rows;
                r.bytes += ch.body.length;
                r.toSeq = mark;
            }
            r.ok = true;
        } catch (Throwable e) {
            r.error = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        }
        r.remaining = store.pendingSince(dev, mark);
        return r;
    }

    private static final class Chunk {
        String filename;
        byte[] body;
        int rows;
        long lastSeq;
    }

    /** Read one chunk's worth of rows and compress them. */
    private static Chunk build(Context c, Store store, String dev, long afterSeq)
            throws Exception {
        Cursor q = store.obsSince(dev, afterSeq, ROWS_PER_CHUNK);
        try {
            if (q.getCount() == 0) return null;

            ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 20);
            GZIPOutputStream gz = new GZIPOutputStream(raw);
            Writer w = new OutputStreamWriter(gz, "UTF-8");

            long first = 0, last = 0;
            int n = 0;

            JSONObject head = new JSONObject();
            head.put("format", CHUNK_FORMAT);
            head.put("app", "boresight");
            head.put("chunk", true);
            head.put("device", dev);
            head.put("deviceName", Sync.deviceName(c));
            head.put("written", System.currentTimeMillis());
            w.write(head.toString());
            w.write('\n');

            while (q.moveToNext()) {
                long seq = q.getLong(0);
                if (n == 0) first = seq;
                last = seq;
                w.write(rowJson(c, q, seq).toString());
                w.write('\n');
                n++;
                if ((n & 0x3FF) == 0) w.flush();
            }
            w.flush();
            w.close();

            Chunk ch = new Chunk();
            ch.rows = n;
            ch.lastSeq = last;
            ch.body = raw.toByteArray();
            ch.filename = name(dev, first, last);
            return ch;
        } finally {
            q.close();
        }
    }

    /**
     * Chunk filename.
     *
     * The sequence range is in the name on purpose: a folder of these sorts into the
     * order they were written, a reader can tell at a glance whether it has a gap, and
     * re-sending a chunk overwrites the same name rather than adding a near-duplicate.
     */
    private static String name(String dev, long from, long to) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        return String.format(Locale.US, "bs-%s-%012d-%012d-%s.ndjson.gz",
                dev, from, to, stamp);
    }

    /** One observation, as the chunk writes it. Column order matches obsSince. */
    private static JSONObject rowJson(Context c, Cursor q, long seq) throws Exception {
        JSONObject o = new JSONObject();
        o.put("seq", seq);
        o.put("session", q.getLong(1));
        o.put("ts", q.getLong(2));
        put(o, "kind", q.getString(3));
        if (!q.isNull(4)) { o.put("lat", q.getDouble(4)); o.put("lon", q.getDouble(5)); }
        if (!q.isNull(6)) o.put("acc", q.getFloat(6));
        if (!q.isNull(8)) o.put("speed", q.getFloat(8));
        if (!q.isNull(9)) o.put("bearing", q.getFloat(9));
        put(o, "tech", q.getString(10));
        put(o, "carrier", q.getString(11));
        put(o, "mcc", q.getString(12));
        put(o, "mnc", q.getString(13));
        put(o, "ident", q.getString(14));
        put(o, "name", q.getString(15));
        put(o, "mac", q.getString(16));
        put(o, "extra", q.getString(17));
        num(o, "pci", q, 18);
        num(o, "tac", q, 19);
        num(o, "arfcn", q, 20);
        num(o, "band", q, 21);
        num(o, "dbm", q, 22);
        num(o, "qual", q, 23);
        num(o, "ta", q, 24);
        if (!q.isNull(25) && q.getInt(25) != 0) o.put("serving", true);
        // The column is "freq" and the field is "dlMhz". Naming the field here instead
        // of the column once threw on every export and truncated the file.
        if (!q.isNull(26)) o.put("dlMhz", q.getDouble(26));
        return o;
    }

    private static void put(JSONObject o, String k, String v) throws Exception {
        if (v != null && !v.isEmpty()) o.put(k, v);
    }

    /** Absent means unknown. Never written as a zero, which would read as a measurement. */
    private static void num(JSONObject o, String k, Cursor q, int col) throws Exception {
        if (!q.isNull(col)) o.put(k, q.getInt(col));
    }

    // ------------------------------------------------------------------ describe

    public static String describe(Context c, Target t) {
        Store s = Store.of(c);
        long mark = s.mark(t.key());
        long waiting = s.pendingSince(s.selfDev(), mark);
        long when = s.markAt(t.key());
        StringBuilder b = new StringBuilder();
        b.append(waiting == 0 ? "Up to date" : waiting + " rows waiting");
        b.append("  ·  last sent ").append(when == 0 ? "never" : Sync.fmt(when));
        long orphans = s.unidentifiedCount();
        if (orphans > 0) {
            b.append("\n\n").append(orphans)
             .append(" rows predate row identity and cannot be sent incrementally. ")
             .append("They are still in the survey and still export whole.");
        }
        return b.toString();
    }
}

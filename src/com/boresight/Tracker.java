package com.boresight;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers every cell seen recently, not just the ones in the latest scan.
 *
 * The modem's neighbour list churns hard: a cell can appear for one sweep and vanish for
 * the next three. Dropping a row the instant it leaves the list makes the screen
 * unreadable and loses the sighting. So each identity is kept for a linger window the
 * user chooses, carrying its own signal history, and is shown as fading once stale.
 *
 * Process-wide, so the live screen, the service and the widget see the same picture.
 */
public final class Tracker {

    /** Per-cell rolling history. */
    public static class Track {
        public String ident;
        public Sampler.Cell last;
        public long first, lastSeen;
        public int sightings;
        public int best = Integer.MIN_VALUE, worst = Integer.MAX_VALUE;

        private static final int CAP = 120;
        private final int[] dbm = new int[CAP];
        private final long[] at = new long[CAP];
        private int count, head;

        void push(Sampler.Cell c, long now) {
            last = c;
            lastSeen = now;
            if (first == 0) first = now;
            sightings++;
            if (Sampler.has(c.dbm)) {
                dbm[head] = c.dbm;
                at[head] = now;
                head = (head + 1) % CAP;
                if (count < CAP) count++;
                if (c.dbm > best) best = c.dbm;
                if (c.dbm < worst) worst = c.dbm;
            }
        }

        /** Oldest-first history of readings. */
        public int[] values() {
            int[] out = new int[count];
            for (int i = 0; i < count; i++) out[i] = dbm[(head - count + i + CAP * 2) % CAP];
            return out;
        }

        public long[] times() {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) out[i] = at[(head - count + i + CAP * 2) % CAP];
            return out;
        }

        public int samples() { return count; }

        public int average() {
            if (count == 0) return Integer.MAX_VALUE;
            long s = 0;
            for (int v : values()) s += v;
            return Math.round((float) s / count);
        }

        /** Signed slope over the last few samples: rising, falling or steady. */
        public int trend() {
            int[] v = values();
            if (v.length < 4) return 0;
            int n = Math.min(8, v.length);
            int half = n / 2;
            long a = 0, b = 0;
            for (int i = v.length - n; i < v.length - half; i++) a += v[i];
            for (int i = v.length - half; i < v.length; i++) b += v[i];
            float d = (float) b / half - (float) a / (n - half);
            if (d >= 1.5f) return 1;
            if (d <= -1.5f) return -1;
            return 0;
        }

        public long ageMs(long now) { return now - lastSeen; }

        public boolean stale(long now) { return now - lastSeen > 2500; }

        /** 1.0 fresh, falling to 0 at the end of the linger window. */
        public float freshness(long now, long window) {
            if (window <= 0) return 1f;
            long age = now - lastSeen;
            if (age <= 2500) return 1f;
            float f = 1f - (age - 2500f) / Math.max(1f, window);
            return Math.max(0f, Math.min(1f, f));
        }
    }

    private static final Tracker SHARED = new Tracker();
    public static Tracker shared() { return SHARED; }

    private final Map<String, Track> tracks = new HashMap<>();
    private long lastSweep;

    /** Fold one scan in and drop anything past the linger window. */
    public synchronized void absorb(Sampler.Snap snap, long lingerMs) {
        long now = snap.ts;
        lastSweep = now;
        for (Sampler.Cell c : snap.cells) {
            String id = c.ident != null ? c.ident : synthetic(c);
            Track t = tracks.get(id);
            if (t == null) {
                t = new Track();
                t.ident = id;
                tracks.put(id, t);
            }
            t.push(c, now);
        }
        prune(now, lingerMs);
    }

    private static String synthetic(Sampler.Cell c) {
        return c.tech + ":pci" + c.pci + "@" + c.arfcn;
    }

    private void prune(long now, long lingerMs) {
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, Track> e : tracks.entrySet()) {
            long age = now - e.getValue().lastSeen;
            // Always keep one sweep's worth so a single missed scan does not clear the list.
            if (age > Math.max(4000, lingerMs)) drop.add(e.getKey());
        }
        for (String k : drop) tracks.remove(k);
    }

    /** Everything currently worth showing: serving first, then by signal, strongest first. */
    public synchronized List<Track> all() {
        List<Track> out = new ArrayList<>(tracks.values());
        Collections.sort(out, new Comparator<Track>() {
            @Override public int compare(Track a, Track b) {
                boolean sa = a.last != null && a.last.serving;
                boolean sb = b.last != null && b.last.serving;
                if (sa != sb) return sa ? -1 : 1;
                int da = a.last != null ? a.last.dbm : Integer.MAX_VALUE;
                int db = b.last != null ? b.last.dbm : Integer.MAX_VALUE;
                boolean ha = Sampler.has(da), hb = Sampler.has(db);
                if (ha != hb) return ha ? -1 : 1;
                if (ha && da != db) return db - da;
                return Long.compare(b.lastSeen, a.lastSeen);
            }
        });
        return out;
    }

    public synchronized Track get(String ident) { return tracks.get(ident); }

    public synchronized int size() { return tracks.size(); }

    public synchronized int liveCount() {
        int n = 0;
        for (Track t : tracks.values()) if (!t.stale(lastSweep)) n++;
        return n;
    }

    public synchronized int lingeringCount() {
        int n = 0;
        for (Track t : tracks.values()) if (t.stale(lastSweep)) n++;
        return n;
    }

    public synchronized void clear() { tracks.clear(); }

    public long sweep() { return lastSweep; }

    /** Convenience: sample, absorb, return the snapshot. */
    public static Sampler.Snap step(Context c, Sampler s) {
        Sampler.Snap snap = s.sample();
        SHARED.absorb(snap, Prefs.linger(c));
        if (snap.serving != null) Trace.shared().push(snap.serving.dbm);
        return snap;
    }
}

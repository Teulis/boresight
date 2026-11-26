package com.boresight;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * An indoor walk test against a floor plan, in the shape a PCTEL-style survey takes.
 *
 * There is no GPS indoors worth having, so position is not measured — it is declared.
 * The surveyor stands somewhere identifiable on the print, taps that spot, walks a
 * straight line to the next identifiable spot, and taps again. Everything the radio
 * reported between those two taps is then laid along the straight line between them,
 * in proportion to when it arrived.
 *
 * That proportionality is the whole trick and the whole weakness. It assumes a
 * constant pace along a straight path, which is why the method wants frequent taps at
 * corners and doors rather than one tap per room. The error is bounded by how far the
 * real path departs from the line, so short legs are accurate and long ones are a
 * guess — worth stating on screen rather than hiding behind a smooth heatmap.
 *
 * Coordinates are pixels in the plan image, not metres and not lat/lon. The image is
 * the coordinate system; a scale is optional and only used to report distances.
 */
public final class Walk {

    /** A tap: "I am here, now". */
    public static final class Point {
        public float x, y;
        public long ts;
        public Point() { }
        public Point(float x, float y, long ts) { this.x = x; this.y = y; this.ts = ts; }
    }

    /**
     * One cell's reading at one instant, positioned once its leg is closed.
     *
     * Every visible cell is recorded, not just the serving one, because the questions
     * afterwards are per band and per carrier: "show me AT&T B66" is unanswerable from
     * serving-cell data alone, since the phone only ever serves on one of them. Several
     * samples therefore share a timestamp, which is what makes them groupable.
     */
    public static final class Sample {
        public long ts;
        public float x = Float.NaN, y = Float.NaN;
        public int dbm = Integer.MAX_VALUE;     // RSRP
        public int rsrq = Integer.MAX_VALUE;
        public int sinr = Integer.MAX_VALUE;
        public int rssi = Integer.MAX_VALUE;
        public int band = Integer.MAX_VALUE;
        public int pci = Integer.MAX_VALUE;
        public int arfcn = Integer.MAX_VALUE;
        public String tech = "", carrier = "";
        public boolean serving;
        public boolean placed() { return !Float.isNaN(x) && !Float.isNaN(y); }

        /** How this reading is labelled in a filter list. */
        public String bandLabel() {
            if (band == Integer.MAX_VALUE) return tech;
            return ("NR".equals(tech) ? "n" : "B") + band;
        }
    }

    /** A view of the walk: which metric, which carrier, which band. */
    public static final class Filter {
        public int metric = F_DBM;
        /** null or empty means every carrier. */
        public String carrier;
        /** Integer.MAX_VALUE means every band. */
        public int band = Integer.MAX_VALUE;
        /** Average across carriers at each instant rather than plotting each. */
        public boolean average;

        public boolean accepts(Sample s) {
            if (carrier != null && !carrier.isEmpty() && !carrier.equals(s.carrier)) return false;
            if (band != Integer.MAX_VALUE && s.band != band) return false;
            return field(s, metric) != Integer.MAX_VALUE;
        }
    }

    public String planName = "";
    public String planFile = "";
    public String label = "";
    public long started = System.currentTimeMillis();

    /** Pixels per metre, or NaN when the plan has not been calibrated. */
    public double pxPerMetre = Double.NaN;

    public final List<Point> points = new ArrayList<>();
    public final List<Sample> samples = new ArrayList<>();

    /** Samples taken since the last tap, not yet positioned. */
    private final List<Sample> pending = new ArrayList<>();

    public boolean recording;

    // ------------------------------------------------------------------ capture

    /** Hold a reading. It gets a position when the next tap closes the leg. */
    public void collect(Sample s) {
        if (!recording || s == null) return;
        pending.add(s);
    }

    /**
     * Drop a waypoint at a point on the plan.
     *
     * The first tap only starts the clock — there is no leg behind it, so anything
     * collected before it is discarded rather than piled onto the start point, which
     * would put a false cluster of readings wherever the surveyor happened to open
     * the screen.
     */
    public void tap(float x, float y, long ts) {
        Point p = new Point(x, y, ts);
        if (points.isEmpty()) {
            points.add(p);
            pending.clear();
            recording = true;
            return;
        }
        Point from = points.get(points.size() - 1);
        points.add(p);
        samples.addAll(spread(pending, from, p));
        pending.clear();
    }

    /**
     * Lay the pending readings along the leg, by time.
     *
     * Exposed and static so the arithmetic can be tested without a plan, a screen or a
     * radio — this is the part that would be wrong in a way nobody notices.
     */
    public static List<Sample> spread(List<Sample> pending, Point from, Point to) {
        List<Sample> out = new ArrayList<>();
        if (pending == null || pending.isEmpty() || from == null || to == null) return out;

        long span = to.ts - from.ts;
        for (Sample s : pending) {
            double f;
            if (span <= 0) {
                // Both taps in the same millisecond, or the clock went backwards.
                // Everything lands on the end point rather than dividing by zero.
                f = 1.0;
            } else {
                f = (double) (s.ts - from.ts) / span;
                if (f < 0) f = 0;
                if (f > 1) f = 1;
            }
            s.x = (float) (from.x + (to.x - from.x) * f);
            s.y = (float) (from.y + (to.y - from.y) * f);
            out.add(s);
        }
        return out;
    }

    /** Readings held but not yet positioned. */
    public int pendingCount() { return pending.size(); }

    public void stop() {
        recording = false;
        pending.clear();
    }

    /**
     * Take back the last tap without throwing away what was measured.
     *
     * The readings on that leg go back into the pending queue, unplaced, so the next
     * tap re-lays them along the corrected line. Deleting them — which an earlier
     * version did — meant a mis-tap cost you the whole corridor and forced you to
     * walk it again. Mistakes are normal on site; losing data to them is not.
     *
     * @return how many readings were returned to the queue
     */
    public int undoLastPoint() {
        if (points.isEmpty()) return 0;
        Point dropped = points.remove(points.size() - 1);
        if (points.isEmpty()) {
            // The start point went: nothing has a leg any more, so everything that
            // was placed becomes pending again, oldest first.
            int n = samples.size();
            List<Sample> back = new ArrayList<>(samples);
            samples.clear();
            for (Sample s : back) { s.x = Float.NaN; s.y = Float.NaN; }
            pending.addAll(0, back);
            sortPending();
            recording = false;
            return n;
        }
        long from = points.get(points.size() - 1).ts;
        List<Sample> back = new ArrayList<>();
        for (int i = samples.size() - 1; i >= 0; i--) {
            Sample s = samples.get(i);
            if (s.ts >= from && s.ts <= dropped.ts) {
                s.x = Float.NaN; s.y = Float.NaN;
                back.add(samples.remove(i));
            }
        }
        pending.addAll(back);
        sortPending();
        recording = true;
        return back.size();
    }

    private void sortPending() {
        java.util.Collections.sort(pending, new java.util.Comparator<Sample>() {
            @Override public int compare(Sample a, Sample b) {
                return Long.compare(a.ts, b.ts);
            }
        });
    }

    public void clear() {
        points.clear();
        samples.clear();
        pending.clear();
        recording = false;
        started = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ measures

    /** Path length in plan pixels. */
    public double pathPixels() {
        double d = 0;
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            d += Math.hypot(b.x - a.x, b.y - a.y);
        }
        return d;
    }

    /** Path length in metres, or NaN when the plan is not calibrated. */
    public double pathMetres() {
        if (Double.isNaN(pxPerMetre) || pxPerMetre <= 0) return Double.NaN;
        return pathPixels() / pxPerMetre;
    }

    public int placedCount() {
        int n = 0;
        for (Sample s : samples) if (s.placed()) n++;
        return n;
    }

    /** Mean of a field across placed samples, ignoring absent readings. */
    public double mean(int which) {
        long sum = 0; int n = 0;
        for (Sample s : samples) {
            int v = field(s, which);
            if (v == Integer.MAX_VALUE) continue;
            sum += v; n++;
        }
        return n == 0 ? Double.NaN : (double) sum / n;
    }

    public static final int F_DBM = 0, F_RSRQ = 1, F_SINR = 2, F_RSSI = 3;

    public static int field(Sample s, int which) {
        switch (which) {
            case F_RSRQ: return s.rsrq;
            case F_SINR: return s.sinr;
            case F_RSSI: return s.rssi;
            default:     return s.dbm;
        }
    }

    public static String metricName(int which) {
        switch (which) {
            case F_RSRQ: return "RSRQ";
            case F_SINR: return "SINR";
            case F_RSSI: return "RSSI";
            default:     return "RSRP";
        }
    }

    public static String metricUnit(int which) {
        return which == F_SINR || which == F_RSRQ ? "dB" : "dBm";
    }

    // ------------------------------------------------------------------ views

    /** Carriers present in the walk, for the filter list. */
    public List<String> carriers() {
        List<String> out = new ArrayList<>();
        for (Sample s : samples) {
            if (s.carrier != null && !s.carrier.isEmpty() && !out.contains(s.carrier)) {
                out.add(s.carrier);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Bands present, as "B66"-style labels paired with their number. */
    public List<int[]> bands() {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (Sample s : samples) if (s.band != Integer.MAX_VALUE) set.add(s.band);
        List<int[]> out = new ArrayList<>();
        for (int b : set) out.add(new int[]{ b });
        return out;
    }

    /**
     * The points to draw, after filtering.
     *
     * With {@link Filter#average} set, readings sharing a timestamp are collapsed to
     * one: the best value per carrier is taken first, then those are averaged. Taking
     * a flat mean over every sample instead would weight whichever carrier happens to
     * expose the most neighbours, which is an artefact of the modem rather than
     * anything about the building.
     */
    public List<Sample> view(Filter f) {
        List<Sample> out = new ArrayList<>();
        if (f == null) f = new Filter();

        if (!f.average) {
            for (Sample s : samples) {
                if (s.placed() && f.accepts(s)) out.add(s);
            }
            return out;
        }

        // ts -> carrier -> best value
        java.util.TreeMap<Long, java.util.HashMap<String, Integer>> byTs = new java.util.TreeMap<>();
        java.util.HashMap<Long, Sample> anchor = new java.util.HashMap<>();
        for (Sample s : samples) {
            if (!s.placed() || !f.accepts(s)) continue;
            int v = field(s, f.metric);
            String who = s.carrier == null || s.carrier.isEmpty() ? "?" : s.carrier;
            java.util.HashMap<String, Integer> m = byTs.get(s.ts);
            if (m == null) { m = new java.util.HashMap<>(); byTs.put(s.ts, m); }
            Integer have = m.get(who);
            if (have == null || v > have) m.put(who, v);
            if (!anchor.containsKey(s.ts)) anchor.put(s.ts, s);
        }
        for (java.util.Map.Entry<Long, java.util.HashMap<String, Integer>> e : byTs.entrySet()) {
            Sample a = anchor.get(e.getKey());
            if (a == null || e.getValue().isEmpty()) continue;
            long sum = 0;
            for (int v : e.getValue().values()) sum += v;
            int mean = (int) Math.round((double) sum / e.getValue().size());

            Sample s = new Sample();
            s.ts = a.ts; s.x = a.x; s.y = a.y;
            s.carrier = "average of " + e.getValue().size();
            s.tech = a.tech;
            switch (f.metric) {
                case F_RSRQ: s.rsrq = mean; break;
                case F_SINR: s.sinr = mean; break;
                case F_RSSI: s.rssi = mean; break;
                default:     s.dbm = mean; break;
            }
            out.add(s);
        }
        return out;
    }

    /** Mean of the filtered view. */
    public double mean(Filter f) {
        long sum = 0; int n = 0;
        for (Sample s : view(f)) {
            int v = field(s, f.metric);
            if (v == Integer.MAX_VALUE) continue;
            sum += v; n++;
        }
        return n == 0 ? Double.NaN : (double) sum / n;
    }

    /** Percent of the filtered view at or above a threshold. */
    public double coverage(Filter f, int threshold) {
        int n = 0, good = 0;
        for (Sample s : view(f)) {
            int v = field(s, f.metric);
            if (v == Integer.MAX_VALUE) continue;
            n++;
            if (v >= threshold) good++;
        }
        return n == 0 ? Double.NaN : 100.0 * good / n;
    }

    /**
     * Fraction of placed samples at or above a threshold — the number an acceptance
     * criterion is written against ("95% of the floor at better than -95 dBm").
     */
    public double coverage(int which, int threshold) {
        int n = 0, good = 0;
        for (Sample s : samples) {
            int v = field(s, which);
            if (v == Integer.MAX_VALUE) continue;
            n++;
            if (v >= threshold) good++;
        }
        return n == 0 ? Double.NaN : 100.0 * good / n;
    }

    // ------------------------------------------------------------------ storage

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("plan", planName);
        o.put("planFile", planFile);
        o.put("label", label);
        o.put("started", started);
        if (!Double.isNaN(pxPerMetre)) o.put("pxPerMetre", pxPerMetre);

        JSONArray pts = new JSONArray();
        for (Point p : points) {
            JSONObject j = new JSONObject();
            j.put("x", p.x); j.put("y", p.y); j.put("ts", p.ts);
            pts.put(j);
        }
        o.put("points", pts);

        JSONArray ss = new JSONArray();
        for (Sample s : samples) {
            if (!s.placed()) continue;
            JSONObject j = new JSONObject();
            j.put("ts", s.ts);
            j.put("x", s.x); j.put("y", s.y);
            if (s.dbm != Integer.MAX_VALUE)   j.put("dbm", s.dbm);
            if (s.rsrq != Integer.MAX_VALUE)  j.put("rsrq", s.rsrq);
            if (s.sinr != Integer.MAX_VALUE)  j.put("sinr", s.sinr);
            if (s.rssi != Integer.MAX_VALUE)  j.put("rssi", s.rssi);
            if (s.serving) j.put("serving", true);
            if (s.band != Integer.MAX_VALUE)  j.put("band", s.band);
            if (s.pci != Integer.MAX_VALUE)   j.put("pci", s.pci);
            if (s.arfcn != Integer.MAX_VALUE) j.put("arfcn", s.arfcn);
            if (!s.tech.isEmpty())    j.put("tech", s.tech);
            if (!s.carrier.isEmpty()) j.put("carrier", s.carrier);
            ss.put(j);
        }
        o.put("samples", ss);
        return o;
    }

    public static Walk fromJson(JSONObject o) {
        Walk w = new Walk();
        if (o == null) return w;
        w.planName = o.optString("plan", "");
        w.planFile = o.optString("planFile", "");
        w.label = o.optString("label", "");
        w.started = o.optLong("started", System.currentTimeMillis());
        w.pxPerMetre = o.has("pxPerMetre") ? o.optDouble("pxPerMetre") : Double.NaN;

        JSONArray pts = o.optJSONArray("points");
        if (pts != null) for (int i = 0; i < pts.length(); i++) {
            JSONObject j = pts.optJSONObject(i);
            if (j == null) continue;
            w.points.add(new Point((float) j.optDouble("x"), (float) j.optDouble("y"),
                    j.optLong("ts")));
        }
        JSONArray ss = o.optJSONArray("samples");
        if (ss != null) for (int i = 0; i < ss.length(); i++) {
            JSONObject j = ss.optJSONObject(i);
            if (j == null) continue;
            Sample s = new Sample();
            s.ts = j.optLong("ts");
            s.x = (float) j.optDouble("x", Double.NaN);
            s.y = (float) j.optDouble("y", Double.NaN);
            s.dbm = j.optInt("dbm", Integer.MAX_VALUE);
            s.rsrq = j.optInt("rsrq", Integer.MAX_VALUE);
            s.sinr = j.optInt("sinr", Integer.MAX_VALUE);
            s.rssi = j.optInt("rssi", Integer.MAX_VALUE);
            s.serving = j.optBoolean("serving", false);
            s.band = j.optInt("band", Integer.MAX_VALUE);
            s.pci = j.optInt("pci", Integer.MAX_VALUE);
            s.arfcn = j.optInt("arfcn", Integer.MAX_VALUE);
            s.tech = j.optString("tech", "");
            s.carrier = j.optString("carrier", "");
            w.samples.add(s);
        }
        return w;
    }

    /** CSV of placed samples, for a report. */
    public String csv() {
        StringBuilder b = new StringBuilder();
        b.append("ts,x_px,y_px,tech,carrier,band,arfcn,pci,serving,"
               + "rsrp_dbm,rsrq_db,sinr_db,rssi_dbm\n");
        for (Sample s : samples) {
            if (!s.placed()) continue;
            b.append(s.ts).append(',');
            b.append(String.format(java.util.Locale.US, "%.1f,%.1f", s.x, s.y)).append(',');
            b.append(s.tech).append(',');
            b.append('"').append(s.carrier.replace("\"", "\"\"")).append('"').append(',');
            b.append(s.band == Integer.MAX_VALUE ? "" : s.band).append(',');
            b.append(s.arfcn == Integer.MAX_VALUE ? "" : s.arfcn).append(',');
            b.append(s.pci == Integer.MAX_VALUE ? "" : s.pci).append(',');
            b.append(s.serving ? "yes" : "no").append(',');
            b.append(s.dbm == Integer.MAX_VALUE ? "" : s.dbm).append(',');
            b.append(s.rsrq == Integer.MAX_VALUE ? "" : s.rsrq).append(',');
            b.append(s.sinr == Integer.MAX_VALUE ? "" : s.sinr).append(',');
            b.append(s.rssi == Integer.MAX_VALUE ? "" : s.rssi).append('\n');
        }
        return b.toString();
    }
}

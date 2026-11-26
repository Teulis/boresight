package com.boresight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The list a DAS technician actually wants off a walk: every distinct carrier, band,
 * downlink, uplink and PCI seen, once each, with the best and last signal.
 *
 * Distinct from Tracker, which is the live view and forgets a cell once it drops out
 * of the scan. This is cumulative for the session: the point is that at the end of a
 * walk you have the full set of frequencies and physical cell IDs in one place rather
 * than having watched them scroll past.
 *
 * Keyed on what makes two sightings the same cell for commissioning purposes —
 * technology, band, channel and PCI. Deliberately not on cell identity: the global ID
 * is often absent on neighbours, and two sectors sharing a channel and PCI are the
 * thing being hunted, not two rows to keep apart.
 */
public final class SessionLog {

    public static final int SORT_SEEN = 0, SORT_SIGNAL = 1, SORT_BAND = 2, SORT_CARRIER = 3;

    public static final class Row {
        public String tech = "", carrier = "", mcc = "", mnc = "";
        public int band = Integer.MAX_VALUE;
        public int arfcn = Integer.MAX_VALUE;
        public int pci = Integer.MAX_VALUE;
        public int tac = Integer.MAX_VALUE;
        public double dlMhz = Double.NaN, ulMhz = Double.NaN;
        public int bwKhz = Integer.MAX_VALUE;
        public int best = Integer.MAX_VALUE;     // strongest dBm seen
        public int last = Integer.MAX_VALUE;     // most recent dBm
        public int worst = Integer.MAX_VALUE;
        public long firstSeen, lastSeen;
        public int sightings;
        public boolean everServing;

        public int family() { return Das.family(tech, band, mcc, mnc); }
        public boolean das() { return family() != Das.OTHER; }

        public String bandLabel() {
            if (!Sampler.has(band)) return tech;
            return ("NR".equals(tech) ? "n" : "B") + band;
        }

        /** "1940.0 / 1860.0" — the pair an installer writes on the sheet. */
        public String duplex() {
            String d = Double.isNaN(dlMhz) ? "—" : Rf.mhz(dlMhz);
            String u = Double.isNaN(ulMhz) ? "—" : Rf.mhz(ulMhz);
            return d + "  /  " + u;
        }

        public String bandwidth() {
            return Sampler.has(bwKhz) ? (bwKhz / 1000) + " MHz" : "—";
        }
    }

    private static SessionLog instance;

    public static synchronized SessionLog shared() {
        if (instance == null) instance = new SessionLog();
        return instance;
    }

    /** Insertion-ordered, so "first seen" order needs no sort. */
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private long started = System.currentTimeMillis();

    private SessionLog() { }

    /** A real operator name, as opposed to the sampler's "-" or "?" placeholders. */
    private static boolean named(String s) {
        if (s == null) return false;
        String t = s.trim();
        return !t.isEmpty() && !"-".equals(t) && !"?".equals(t);
    }

    private static String key(Sampler.Cell c) {
        return c.tech + "/" + c.band + "/" + c.arfcn + "/" + c.pci;
    }

    /** Fold one scan into the log. */
    public synchronized void add(Sampler.Snap snap) {
        if (snap == null) return;
        for (Sampler.Cell c : snap.cells) {
            if (c == null) continue;
            // A cell with neither channel nor PCI carries nothing worth logging and
            // would collide with every other such sighting.
            if (!Sampler.has(c.arfcn) && !Sampler.has(c.pci)) continue;

            String k = key(c);
            Row r = rows.get(k);
            if (r == null) {
                r = new Row();
                r.tech = c.tech;
                r.band = c.band;
                r.arfcn = c.arfcn;
                r.pci = c.pci;
                r.firstSeen = snap.ts;
                rows.put(k, r);
            }
            // Later sightings fill in what the first lacked. Neighbour measurements
            // are sparse, and the same cell reports more of itself once it becomes
            // the serving one.
            // "-" is the sampler's placeholder for "no operator reported", not a name.
            // Storing it produced rows labelled "-" and a carrier summary reading
            // "- · AT&T".
            if (named(c.carrier)) r.carrier = c.carrier;
            else if (r.carrier.isEmpty() && named(snap.operator)) r.carrier = snap.operator;
            if (c.mcc != null && !c.mcc.isEmpty()) r.mcc = c.mcc;
            if (c.mnc != null && !c.mnc.isEmpty()) r.mnc = c.mnc;
            if (Sampler.has(c.tac)) r.tac = c.tac;
            if (!Double.isNaN(c.dlMhz)) r.dlMhz = c.dlMhz;
            if (!Double.isNaN(c.ulMhz)) r.ulMhz = c.ulMhz;
            if (Sampler.has(c.bwKhz)) r.bwKhz = c.bwKhz;

            if (Sampler.has(c.dbm)) {
                r.last = c.dbm;
                if (!Sampler.has(r.best) || c.dbm > r.best) r.best = c.dbm;
                if (!Sampler.has(r.worst) || c.dbm < r.worst) r.worst = c.dbm;
            }
            if (c.serving) r.everServing = true;
            r.lastSeen = snap.ts;
            r.sightings++;
        }
    }

    public synchronized List<Row> rows(int sortBy) { return rows(sortBy, false); }

    /**
     * @param dasOnly keep only LTE on the DAS band families — 700, FirstNet, cellular
     *                850, PCS and AWS. The default for site work, where the macro
     *                network's NR and TDD carriers are noise rather than data.
     */
    public synchronized List<Row> rows(int sortBy, boolean dasOnly) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows.values()) if (!dasOnly || r.das()) out.add(r);

        switch (sortBy) {
            case SORT_SIGNAL:
                Collections.sort(out, new Comparator<Row>() {
                    @Override public int compare(Row a, Row b) {
                        return Integer.compare(norm(b.best), norm(a.best));
                    }
                });
                break;
            case SORT_BAND:
                // Family order first, so the list reads the way a sweep sheet does:
                // 700, FirstNet, 850, PCS, AWS, then anything else.
                Collections.sort(out, new Comparator<Row>() {
                    @Override public int compare(Row a, Row b) {
                        int f = Integer.compare(Das.order(a.family()), Das.order(b.family()));
                        if (f != 0) return f;
                        int x = Integer.compare(norm(a.band), norm(b.band));
                        if (x != 0) return x;
                        int c = a.carrier.compareToIgnoreCase(b.carrier);
                        if (c != 0) return c;
                        return Integer.compare(norm(a.pci), norm(b.pci));
                    }
                });
                break;
            case SORT_CARRIER:
                Collections.sort(out, new Comparator<Row>() {
                    @Override public int compare(Row a, Row b) {
                        int t = a.carrier.compareToIgnoreCase(b.carrier);
                        if (t != 0) return t;
                        int f = Integer.compare(Das.order(a.family()), Das.order(b.family()));
                        if (f != 0) return f;
                        return Integer.compare(norm(a.band), norm(b.band));
                    }
                });
                break;
            default: /* first seen — the insertion order */ break;
        }
        return out;
    }

    private static int norm(int v) { return Sampler.has(v) ? v : Integer.MIN_VALUE; }

    public synchronized int size() { return rows.size(); }

    public synchronized int dasSize() {
        int n = 0;
        for (Row r : rows.values()) if (r.das()) n++;
        return n;
    }

    public synchronized long startedAt() { return started; }

    public synchronized void clear() {
        rows.clear();
        started = System.currentTimeMillis();
    }

    /** Distinct carriers seen, for the header line. */
    public synchronized List<String> carriers(boolean dasOnly) {
        List<String> out = new ArrayList<>();
        for (Row r : rows.values()) {
            if (dasOnly && !r.das()) continue;
            if (!r.carrier.isEmpty() && !out.contains(r.carrier)) out.add(r.carrier);
        }
        Collections.sort(out);
        return out;
    }

    /** Counts per DAS family, in sheet order: "700 3 · FN 1 · PCS 2". */
    public synchronized String familySummary() {
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        for (Row r : rows.values()) {
            int fam = r.family();
            if (fam == Das.OTHER) continue;
            Integer c = counts.get(Das.order(fam));
            counts.put(Das.order(fam), c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) return "no DAS bands seen yet";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (b.length() > 0) b.append("   ·   ");
            b.append(Das.shortName(famForOrder(e.getKey())))
             .append(' ').append(e.getValue());
        }
        return b.toString();
    }

    private static int famForOrder(int order) {
        for (int f : new int[]{Das.B700, Das.FIRSTNET, Das.CELL, Das.PCS, Das.AWS}) {
            if (Das.order(f) == order) return f;
        }
        return Das.OTHER;
    }

    /**
     * The log as CSV, in the column order a commissioning sheet wants.
     *
     * Written here rather than in Export because this is a different shape from the
     * wardrive log: one row per distinct cell rather than one per sighting.
     */
    public synchronized String csv(boolean dasOnly) {
        StringBuilder b = new StringBuilder();
        b.append("family,tech,carrier,mcc,mnc,band,arfcn,pci,tac,dl_mhz,ul_mhz,bw_mhz,"
               + "best_dbm,worst_dbm,last_dbm,sightings,serving\n");
        for (Row r : rows(SORT_BAND, dasOnly)) {
            b.append(Das.name(r.family())).append(',');
            b.append(r.tech).append(',');
            b.append('"').append(r.carrier.replace("\"", "\"\"")).append('"').append(',');
            b.append(r.mcc).append(',').append(r.mnc).append(',');
            b.append(Sampler.has(r.band) ? String.valueOf(r.band) : "").append(',');
            b.append(Sampler.has(r.arfcn) ? String.valueOf(r.arfcn) : "").append(',');
            b.append(Sampler.has(r.pci) ? String.valueOf(r.pci) : "").append(',');
            b.append(Sampler.has(r.tac) ? String.valueOf(r.tac) : "").append(',');
            b.append(Double.isNaN(r.dlMhz) ? "" : String.format(Locale.US, "%.1f", r.dlMhz)).append(',');
            b.append(Double.isNaN(r.ulMhz) ? "" : String.format(Locale.US, "%.1f", r.ulMhz)).append(',');
            b.append(Sampler.has(r.bwKhz) ? String.valueOf(r.bwKhz / 1000) : "").append(',');
            b.append(Sampler.has(r.best) ? String.valueOf(r.best) : "").append(',');
            b.append(Sampler.has(r.worst) ? String.valueOf(r.worst) : "").append(',');
            b.append(Sampler.has(r.last) ? String.valueOf(r.last) : "").append(',');
            b.append(r.sightings).append(',');
            b.append(r.everServing ? "yes" : "no").append('\n');
        }
        return b.toString();
    }

    /** Plain text, for pasting into a message or a site note. */
    public synchronized String text(boolean dasOnly) {
        StringBuilder b = new StringBuilder();
        for (Row r : rows(SORT_BAND, dasOnly)) {
            b.append(String.format(Locale.US, "%-4s %-5s %-14s %-6s PCI %-5s %s   %s",
                    Das.shortName(r.family()),
                    r.bandLabel(),
                    r.carrier.isEmpty() ? "-" : r.carrier,
                    Sampler.has(r.arfcn) ? "#" + r.arfcn : "-",
                    Sampler.has(r.pci) ? String.valueOf(r.pci) : "-",
                    r.duplex(),
                    Sampler.has(r.best) ? r.best + " dBm" : ""));
            b.append('\n');
        }
        return b.toString();
    }
}

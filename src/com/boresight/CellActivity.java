package com.boresight;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Everything the log holds about one cell identity, and the position estimate that falls
 * out of it.
 *
 * Worth having as its own screen because a single cell is the unit of work when you are
 * aiming: you pick one sector, drive it, and want its whole record in front of you rather
 * than a row in a list.
 */
public class CellActivity extends Screen {

    private String ident;
    private Store store;
    private List<Store.Obs> history;

    /** Accumulated as the screen is built, so the copy action has something to hand over. */
    private final StringBuilder dump = new StringBuilder();

    @Override protected String title() { return "CELL"; }

    @Override protected void content() {
        ident = getIntent().getStringExtra("ident");
        store = Store.of(this);

        if (ident == null || ident.isEmpty()) {
            LinearLayout box = pane("No cell");
            hint(box, "This screen needs a cell identity. Open it from a row on the live "
                    + "page, the map or the history list.");
            status("no identity", "");
            return;
        }

        history = store.historyOf(ident, 2000);
        header();
        trace();
        estimate();
        live();
        sightings();
        actions();
    }

    // ------------------------------------------------------------------ header

    private void header() {
        LinearLayout box = pane("Identity");

        Store.Obs latest = history.isEmpty() ? null : history.get(history.size() - 1);
        Tracker.Track live = Tracker.shared().get(ident);
        Sampler.Cell now = live == null ? null : live.last;

        String tech = latest != null ? latest.tech : (now != null ? now.tech : null);
        String carrier = latest != null ? latest.carrier : (now != null ? now.carrier : null);

        line(box, "Identity", ident);
        line(box, "Technology", tech);
        line(box, "Carrier", carrier == null ? "unattributed" : carrier);

        if (history.isEmpty()) {
            hint(box, "Nothing logged for this cell yet. Turn recording on to build a "
                    + "history; the live page still shows it in the meantime.");
            status(ident, "not logged");
            return;
        }

        int best = Integer.MIN_VALUE, worst = Integer.MAX_VALUE, n = 0;
        long sum = 0;
        for (Store.Obs o : history) {
            if (!Sampler.has(o.dbm)) continue;
            best = Math.max(best, o.dbm);
            worst = Math.min(worst, o.dbm);
            sum += o.dbm;
            n++;
        }

        line(box, "Sightings", String.valueOf(history.size()));
        if (n > 0) {
            line(box, "Best", best + " dBm");
            line(box, "Mean", Math.round((float) sum / n) + " dBm");
            line(box, "Worst", worst + " dBm");
        }

        long nowMs = System.currentTimeMillis();
        Store.Obs first = history.get(0);
        line(box, "First seen", MainActivity.fmtAge(nowMs - first.ts) + " ago");
        line(box, "Last seen", MainActivity.fmtAge(nowMs - latest.ts) + " ago");

        if (Sampler.has(latest.band)) {
            String b = ("NR".equals(latest.tech) ? "n" : "B") + latest.band
                     + (Rf.isTdd(latest.band) ? "  TDD" : "");
            line(box, "Band", b);
        }
        if (Sampler.has(latest.arfcn)) line(box, "ARFCN", String.valueOf(latest.arfcn));
        if (Sampler.has(latest.pci)) line(box, "PCI", String.valueOf(latest.pci));
        if (Sampler.has(latest.tac)) line(box, "TAC", String.valueOf(latest.tac));

        String dl = dlFor(latest), ul = ulFor(latest);
        if (dl != null) line(box, "Downlink", dl);
        if (ul != null) line(box, "Uplink", ul);

        int taMax = Integer.MIN_VALUE;
        for (Store.Obs o : history) if (Sampler.has(o.ta)) taMax = Math.max(taMax, o.ta);
        if (taMax != Integer.MIN_VALUE) {
            line(box, "Closest timing advance",
                    taFor(closestTa()) + "  (nearest approach)");
        }

        status(carrier == null ? ident : carrier + "  " + ident,
                history.size() + " logged");
    }

    private int closestTa() {
        int min = Integer.MAX_VALUE;
        for (Store.Obs o : history) {
            if (Sampler.has(o.ta) && o.ta > 0 && o.ta < min) min = o.ta;
        }
        return min;
    }

    // ------------------------------------------------------------------ trace

    private void trace() {
        if (history.isEmpty()) return;

        int n = 0;
        for (Store.Obs o : history) if (Sampler.has(o.dbm)) n++;
        if (n < 2) return;

        LinearLayout box = pane("Logged signal");
        Graph g = graph(box, Skin.CH_RSRP, "dBm", -135, -60, 25, 110);

        float[] series = new float[n];
        int i = 0;
        for (Store.Obs o : history) if (Sampler.has(o.dbm)) series[i++] = o.dbm;
        g.setSeries(series);

        hint(box, "Every logged reading for this cell, oldest on the left. The shape is "
                + "the drive, not time: samples are only written where the log was running "
                + "and the fix was good enough.");
    }

    // ------------------------------------------------------------------ estimate

    private void estimate() {
        LinearLayout box = pane("Tower estimate");

        List<Locate.Point> pts = Locate.pointsFor(store, ident, 2000);
        if (pts.size() < 2) {
            hint(box, "Needs at least two located sightings from different places. Drive "
                    + "the cell with recording on and come back — timing advance readings, "
                    + "where LTE gives them, are worth far more than extra power readings.");
            return;
        }

        Locate.Fix f = Locate.solve(pts,
                Prefs.integer(this, "eirp_dbm", 60),
                Prefs.real(this, "pl_exponent", 3.0f),
                true);

        line(box, "Method", f.method);
        line(box, "Quality", f.quality + " of 100");
        line(box, "Sightings used", f.used
                + (f.taUsed > 0 ? "   (" + f.taUsed + " with timing advance)" : ""));

        if (!f.ok()) {
            hint(box, f.caveat.isEmpty() ? "Not enough agreement to place it." : f.caveat);
            return;
        }

        line(box, "Position", String.format(Locale.US, "%.6f, %.6f", f.lat, f.lon));
        line(box, "Uncertainty", Math.round(f.majorM) + " x " + Math.round(f.minorM)
                + " m,  major " + Math.round(f.bearingDeg) + "°");
        line(box, "Residual", Math.round(f.rmsM) + " m rms");
        line(box, "Mean range", TileMapView.fmtDistance(f.meanRangeM));
        line(box, "Propagation model", String.format(Locale.US,
                "%s  %.0f dBm EIRP,  n=%.2f",
                f.fitted ? "fitted from timing advance" : "assumed",
                f.eirpDbm, f.exponent));

        Location me = MonitorService.lastFix();
        if (me != null) {
            double d = Locate.distanceM(me.getLatitude(), me.getLongitude(), f.lat, f.lon);
            double br = Locate.bearingTo(me.getLatitude(), me.getLongitude(), f.lat, f.lon);
            TextView v = kv(box, "Point this way",
                    TileMapView.fmtDistance(d) + "   " + Math.round(br) + "° "
                    + Locate.compass(br));
            v.setTextColor(skin.accent(Skin.CH_RSRP));
            dump.append("Point this way: ").append(TileMapView.fmtDistance(d))
                .append(' ').append(Math.round(br)).append(' ')
                .append(Locate.compass(br)).append('\n');
        }

        if (!f.caveat.isEmpty()) hint(box, f.caveat);
    }

    // ------------------------------------------------------------------ live

    private void live() {
        Tracker.Track t = Tracker.shared().get(ident);
        if (t == null || t.last == null) return;

        LinearLayout box = pane("Live");
        Sampler.Cell c = t.last;
        long now = System.currentTimeMillis();

        TextView v = kv(box, "Now",
                Sampler.has(c.dbm) ? c.dbm + " dBm" : "no level");
        v.setTextColor(th.grade(c.dbm));

        int tr = t.trend();
        line(box, "Trend", tr > 0 ? "rising" : tr < 0 ? "falling" : "steady");
        line(box, "This session", t.sightings
                + (t.sightings == 1 ? " sighting" : " sightings"));
        if (Sampler.has(t.best)) line(box, "Session best", t.best + " dBm");
        if (Sampler.has(t.average())) line(box, "Session mean", t.average() + " dBm");
        line(box, "Last swept", t.stale(now)
                ? MainActivity.fmtAge(now - t.lastSeen) + " ago" : "this sweep");

        if (t.samples() >= 2) {
            Graph.Spark spark = new Graph.Spark(this, th);
            spark.range(-135, -60);
            spark.set(t.values(), th.grade(c.dbm), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, th.dp(40));
            lp.setMargins(0, th.dp(6), 0, 0);
            box.addView(spark, lp);
        }
    }

    // ------------------------------------------------------------------ sightings

    private void sightings() {
        if (history.isEmpty()) return;

        int show = Math.min(40, history.size());
        LinearLayout box = pane("Recent sightings  ·  " + show
                + " of " + history.size());

        SimpleDateFormat clock = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        for (int i = history.size() - 1; i >= history.size() - show; i--) {
            Store.Obs o = history.get(i);
            StringBuilder s = new StringBuilder();
            s.append(clock.format(new Date(o.ts)));
            s.append(Sampler.has(o.dbm)
                    ? pad(o.dbm + " dBm", 10) : pad("--", 10));
            if (Sampler.has(o.ta)) s.append(pad(taFor(o.ta), 14));
            if (o.acc > 0) s.append(pad("±" + Math.round(o.acc) + "m", 8));
            if (o.located()) {
                s.append(String.format(Locale.US, " %.5f,%.5f", o.lat, o.lon));
            }
            TextView row = mono(box, s.toString(), 9.5f,
                    Sampler.has(o.dbm) ? th.grade(o.dbm) : skin.inkSecondary);
            row.setSingleLine(true);
            dump.append(s).append('\n');
        }

        hint(box, "Timing advance is the network telling the handset how early to "
                + "transmit. One step is about 78 m of path, so it is a measured range "
                + "rather than a modelled one.");
    }

    // ------------------------------------------------------------------ actions

    private void actions() {
        LinearLayout box = pane("Actions");
        LinearLayout row = actionRow(box);

        smallAction(row, "Map", new Runnable() {
            @Override public void run() {
                startActivity(new Intent(CellActivity.this, MapActivity.class));
            }
        });
        smallAction(row, "Copy", new Runnable() {
            @Override public void run() {
                ClipboardManager cb = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("cell " + ident, dump.toString()));
                toast("Detail copied");
            }
        });
        smallAction(row, "Export CSV", new Runnable() {
            @Override public void run() {
                Export.Result r = Export.csv(CellActivity.this,
                        store.historyOf(ident, 20_000));
                if (r.ok) info("Exported", r.rows + " rows written to\n\n" + r.where);
                else info("Export failed", r.error == null ? "Unknown error" : r.error);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** A readout row that also lands in the copy buffer. */
    private void line(LinearLayout into, String label, String value) {
        if (value == null) return;
        kv(into, label, value);
        dump.append(label).append(": ").append(value).append('\n');
    }

    private static String taFor(int ta) {
        if (!Sampler.has(ta)) return "-";
        return "TA " + ta + " ~" + Math.round(ta * Locate.TA_METRES) + "m";
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder("  ").append(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    private String dlFor(Store.Obs o) {
        if (!Double.isNaN(o.dlMhz)) return Rf.mhz(o.dlMhz);
        if (!Sampler.has(o.arfcn)) return null;
        double dl = "NR".equals(o.tech)
                ? Rf.nrMhz(o.arfcn)
                : Rf.lteMhz(bandOf(o), o.arfcn);
        return Double.isNaN(dl) ? null : Rf.mhz(dl);
    }

    private String ulFor(Store.Obs o) {
        int band = bandOf(o);
        if (!Sampler.has(band)) return null;
        double ul;
        if ("NR".equals(o.tech)) {
            double dl = !Double.isNaN(o.dlMhz) ? o.dlMhz : Rf.nrMhz(o.arfcn);
            ul = Rf.isTdd(band) ? dl : Rf.nrUlMhz(band, dl);
        } else {
            if (!Sampler.has(o.arfcn)) return null;
            ul = Rf.lteUlMhz(band, o.arfcn);
        }
        return Double.isNaN(ul) ? null : Rf.mhz(ul);
    }

    private static int bandOf(Store.Obs o) {
        if (Sampler.has(o.band)) return o.band;
        if (!Sampler.has(o.arfcn) || "NR".equals(o.tech)) return Integer.MAX_VALUE;
        return Rf.lteBandFor(o.arfcn);
    }
}

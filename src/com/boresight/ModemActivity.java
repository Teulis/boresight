package com.boresight;

import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Modulation and link quality: what the channel is actually carrying.
 *
 * The honest framing matters here. CQI, SINR, RSRQ and bandwidth are measured. The
 * modulation order is a table lookup from CQI, which is exact. The constellation is
 * synthesised from the measured SINR, because Android hands out no raw IQ at any
 * permission level — the symbols never leave the modem. So the picture responds to the
 * real channel while you aim, but it is a model of the measurement, not a capture of it.
 */
public class ModemActivity extends Screen {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private Sampler sampler;
    private Constellation iq;
    private Graph sinrGraph, cqiGraph;
    private TextView modValue, cqiValue, rateValue, effValue, evmValue, ceilValue,
            shannonValue, bwValue, sourceNote;
    private TextView aggSummary, aggSource, aggList;

    @Override protected String title() { return "MODULATION"; }

    @Override protected void content() {
        sampler = new Sampler(this);

        // --- constellation ------------------------------------------------
        LinearLayout box = pane("Constellation");
        iq = new Constellation(this, th, skin);
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.height = th.dp(300);
        lp.setMargins(0, th.dp(4), 0, 0);
        box.addView(iq, lp);

        sourceNote = th.text(this,
                "Symbol clouds are generated from the measured SINR on the modulation "
              + "the CQI reports. Android exposes no baseband IQ, so this is a model of "
              + "the live channel rather than captured symbols — the spread is real, the "
              + "individual dots are not.",
                10, Skin.alpha(skin.inkSecondary, 0.95f));
        sourceNote.setPadding(0, th.dp(7), 0, 0);
        sourceNote.setLineSpacing(th.dpf(1.5f), 1f);
        box.addView(sourceNote);

        // --- the numbers --------------------------------------------------
        LinearLayout nums = pane("Link");
        modValue     = kv(nums, "Modulation", "-");
        cqiValue     = kv(nums, "CQI", "-");
        rateValue    = kv(nums, "Code rate", "-");
        effValue     = kv(nums, "Spectral efficiency", "-");
        evmValue     = kv(nums, "Implied EVM", "-");
        bwValue      = kv(nums, "Bandwidth", "-");
        ceilValue    = kv(nums, "Physical layer ceiling", "-");
        shannonValue = kv(nums, "Shannon limit at this SINR", "-");

        hint(nums, "The ceiling is one layer of this carrier at the current efficiency, "
                 + "before MIMO, protocol overhead and whatever the scheduler gives you. "
                 + "Treat it as the shape of the headroom, not a speed test.");

        // --- traces -------------------------------------------------------
        LinearLayout traces = pane("Quality over time");
        sinrGraph = graph(traces, Skin.CH_SINR, "SINR dB", -10, 35, 10, 90);
        cqiGraph = graph(traces, Skin.CH_RSRQ, "CQI", 0, 15, 5, 90);

        // --- carrier aggregation -------------------------------------------
        LinearLayout agg = pane("Carrier aggregation");
        aggSummary = kv(agg, "Carriers", "-");
        aggSource  = kv(agg, "Reported by", "-");
        aggList = mono(agg, "", 11, skin.inkPrimary);
        hint(agg, "Each component carrier the modem is using, primary first. How much "
                + "of it you get depends on the backend. On its own the app reads the "
                + "service state, which gives the count and every bandwidth but no "
                + "bands — except the primary's, which is knowable because that is the "
                + "cell we are registered on. With a privileged backend attached it "
                + "reads the physical channel configuration instead, which carries the "
                + "band and PCI of every carrier including the secondaries. Android "
                + "reserves that to signature-level apps; the shell holds it, and "
                + "Shizuku runs as the shell.");
        hint(agg, "Measured against service mode on a Galaxy S25 Ultra and it agrees "
                + "carrier for carrier. Two things to know: on 5G standalone this modem "
                + "publishes no bandwidths and no usable channel configuration, so "
                + "nothing is reported rather than a single carrier invented; and an "
                + "idle SIM slot publishes a placeholder entry with every field zeroed, "
                + "which is discarded rather than counted as a carrier.");

        LinearLayout tools = pane("Beyond the public API");
        hint(tools, "Block error rate, per-layer MCS, rank indicator and the real "
                  + "constellation live in Samsung's service mode, which only the dialer "
                  + "can open. These two shortcuts go there.");
        LinearLayout row = actionRow(tools);
        smallAction(row, "*#0011#", new Runnable() {
            @Override public void run() { dial("*#0011"); }
        });
        smallAction(row, "*#2263#", new Runnable() {
            @Override public void run() { dial("*#2263"); }
        });
    }

    /**
     * The component list, refreshed on the same tick as everything else. An empty list
     * is shown as "not reported" rather than as one carrier: on a modem that declines
     * to publish this, claiming a single carrier would be inventing an answer.
     */
    private void paintAggregation() {
        // Kicks off a background read when a privileged backend is attached and the
        // last answer has gone stale; returns immediately otherwise.
        AggFeed.refresh(this);
        java.util.List<Agg.Cc> ccs = Agg.components(this);
        aggSummary.setText(Agg.summary(this));
        aggSource.setText(ccs.isEmpty() ? "nothing answered" : Agg.source());
        if (ccs.isEmpty()) {
            aggList.setText("");
            aggList.setVisibility(android.view.View.GONE);
            return;
        }
        aggList.setVisibility(android.view.View.VISIBLE);
        StringBuilder b = new StringBuilder();
        for (Agg.Cc cc : ccs) {
            if (b.length() > 0) b.append('\n');
            b.append(cc.toString());
        }
        aggList.setText(b.toString());
    }

    private void dial(String partial) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL,
                    android.net.Uri.parse("tel:" + android.net.Uri.encode(partial))));
            toast("Press # on the dialpad");
        } catch (Exception e) {
            toast("No dialer available");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        // The physical-channel callback only delivers while something is listening, and
        // the first list arrives asynchronously, so start it before the first tick.
        Agg.start(this);
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void tick() {
        if (!running) return;
        refresh();
        handler.postDelayed(new Runnable() { @Override public void run() { tick(); } },
                Math.max(500, Prefs.refresh(this)));
    }

    private void refresh() {
        paintAggregation();

        Sampler.Snap snap = Tracker.step(this, sampler);
        Sampler.Cell s = snap.serving;

        if (s == null) {
            iq.setStale();
            status("no serving cell", snap.title());
            return;
        }

        int table = Sampler.has(s.cqiTable) && s.cqiTable == 2 ? 2 : 1;
        Mcs.Entry e = Sampler.has(s.cqi) ? Mcs.forCqi(s.cqi, table) : null;

        String mod;
        if (e != null) {
            mod = e.modulation;
            modValue.setText(mod + "   (CQI table " + table + ")");
            cqiValue.setText(String.valueOf(s.cqi));
            rateValue.setText(String.format(java.util.Locale.US, "%.3f   (%d/1024)",
                    e.codeRate(), e.codeRate1024));
            effValue.setText(String.format(java.util.Locale.US, "%.4f b/s/Hz", e.efficiency));
            cqiGraph.push(s.cqi);
        } else {
            // No CQI from the modem; fall back to what the SINR could support, and say so.
            mod = Mcs.modulationForSinr(s.qual);
            modValue.setText(mod == null ? "-" : mod + "   (inferred from SINR)");
            cqiValue.setText("not reported");
            rateValue.setText("-");
            effValue.setText("-");
            cqiGraph.pushGap();
        }

        if (Sampler.has(s.qual)) {
            sinrGraph.push(s.qual);
            evmValue.setText(String.format(java.util.Locale.US, "%.1f %%",
                    Mcs.evmPercent(s.qual)));
            shannonValue.setText(String.format(java.util.Locale.US, "%.2f b/s/Hz",
                    Mcs.shannonEfficiency(s.qual)));
            iq.set(mod, s.qual);
        } else {
            sinrGraph.pushGap();
            evmValue.setText("no SINR reported");
            shannonValue.setText("-");
            iq.set(mod, Double.NaN);
        }

        String bw = Rf.bw(s.bwKhz, Integer.MAX_VALUE);
        bwValue.setText("-".equals(bw) ? "not reported" : bw);

        double eff = e != null ? e.efficiency
                : (Sampler.has(s.qual) ? Mcs.shannonEfficiency(s.qual) * 0.6 : Double.NaN);
        double ceil = Mcs.ceilingMbps(eff, s.bwKhz, 1);
        ceilValue.setText(Double.isNaN(ceil) ? "-"
                : String.format(java.util.Locale.US, "%.0f Mbit/s", ceil));

        status(s.tech + "  " + (s.carrier == null ? "" : s.carrier),
                Sampler.has(s.dbm) ? s.dbm + " dBm" : "");
    }
}

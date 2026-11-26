package com.boresight;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The live page: quadrant traces across the top, the serving cell in full, then every cell
 * seen inside the linger window, each with its own history.
 *
 * Laid out like a desktop monitor rather than a phone app, which is the point: dense rows,
 * monospace numerics, and plots that scroll against a fixed grid.
 */
public class MainActivity extends Activity {

    private static final int REQ = 7;
    private static final String TAB_ALL = "All", TAB_UNKNOWN = "Unknown";

    private Theme th;
    private Skin skin;
    private Sampler sampler;
    private android.telephony.TelephonyManager tm;
    private Object strengthCallback;
    private Drawer drawer;
    private Header header;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    // Quadrants
    private final List<Graph> graphs = new ArrayList<>();
    private final List<Graph.Led> leds = new ArrayList<>();
    private final List<TextView> quadValues = new ArrayList<>();
    private final List<String> quadChannels = new ArrayList<>();

    // Serving block
    private TextView bigValue, servingLabel, servingIdent, servingRf;
    private TextView statBand, statDl, statUl, statBw, statRsrq;
    private Graph.Led servingBars;

    // Cell list
    private LinearLayout cellColumn;
    private FlowLayout tabStrip;
    private TextView statusLeft, statusRight;

    private String selectedTab = TAB_ALL;
    private String lastReport = "";

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        th = new Theme(this);
        skin = Skin.current(this);
        sampler = new Sampler(this);
        tm = (android.telephony.TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        LinearLayout root = th.column(this);
        root.setBackgroundColor(skin.windowFlat());
        header = new Header(this, th, skin, "BORESIGHT", new Runnable() {
            @Override public void run() { drawer.toggle(); }
        });
        root.addView(header, Theme.wrap());
        View page = buildPage();
        root.addView(page, Theme.grow());

        drawer = Drawer.attach(this, th, skin, root);
        th.useSkin(skin);
        th.systemBars(this);
        Header.inset(this, header, page, th, drawer);
        buildMenu();
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(skin.windowFlat()));

        // Before anything that can fail, so the first thing in the log is what this
        // build is and what it is running on.
        Logs.boot(this);
        Notif.ensureChannels(this);
        // The welcome page explains the permissions before the platform asks, so on a
        // first run it goes first and this screen asks for nothing.
        if (!firstRun()) {
            ensurePermissions();
            offerShizuku();
            // Component carriers only arrive while something is registered, and the
            // first list is asynchronous, so start listening as early as possible.
            Agg.start(this);
        }
    }

    /**
     * Ask Shizuku for access once, and only if it is already there and running.
     *
     * Deliberately one-shot and silent otherwise. Shizuku is an enhancement, not a
     * dependency: nothing here nags, nothing installs it, and declining leaves every
     * feature working on the app's own uid. Asking at all is worth it only because the
     * alternative is the user never discovering that the features which say "needs a
     * privileged backend" could have had one.
     */
    private void offerShizuku() {
        try {
            Shell sh = Shell.get(this);
            Shell.ShizukuExecutor z = sh.shizuku();
            if (!z.installed() || !z.serviceRunning() || z.permitted()) return;
            android.content.SharedPreferences p =
                    getSharedPreferences("boresight", MODE_PRIVATE);
            if (p.getBoolean("shizuku_asked", false)) return;
            p.edit().putBoolean("shizuku_asked", true).apply();
            z.requestPermission(4242);
        } catch (Throwable ignored) { }
    }

    @Override protected void onResume() {
        super.onResume();
        Skin now = Skin.current(this);
        if (!now.id.equals(skin.id)) { recreate(); return; }
        running = true;
        rebuildTabs();
        listenForStrength();
        tick();
    }

    /**
     * Poll rate alone cannot make the trace livelier: getAllCellInfo is served from a
     * cache the modem refreshes about once a second, so asking faster just redraws the
     * same number. Signal strength, by contrast, is pushed to us whenever the modem has
     * something new, so this feeds the trace between polls and is what actually makes
     * the line move.
     */
    private void listenForStrength() {
        if (tm == null || strengthCallback != null) return;
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        if (!granted()) return;
        try {
            android.telephony.TelephonyCallback cb =
                    new StrengthWatcher();
            tm.registerTelephonyCallback(getMainExecutor(), cb);
            strengthCallback = cb;
        } catch (Throwable ignored) {}
    }

    private class StrengthWatcher extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.SignalStrengthsListener {
        @Override public void onSignalStrengthsChanged(
                android.telephony.SignalStrength s) {
            if (s == null) return;
            int dbm = Integer.MAX_VALUE;
            for (android.telephony.CellSignalStrength c : s.getCellSignalStrengths()) {
                int d = c.getDbm();
                if (Sampler.has(d) && d != 0) { dbm = d; break; }
            }
            if (!Sampler.has(dbm)) return;
            Trace.shared().push(dbm);
            for (int i = 0; i < quadChannels.size(); i++) {
                if (!Skin.CH_RSRP.equals(quadChannels.get(i))) continue;
                Graph g = graphs.get(i);
                if (g != null) g.push(dbm);
                Graph.Led l = leds.get(i);
                if (l != null) l.set(dbm);
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (strengthCallback instanceof android.telephony.TelephonyCallback) {
            try {
                tm.unregisterTelephonyCallback(
                        (android.telephony.TelephonyCallback) strengthCallback);
            } catch (Throwable ignored) {}
            strengthCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (drawer != null && drawer.isOpen()) { drawer.close(); return; }
        super.onBackPressed();
    }

    /**
     * Title-bar overflow. Replaces the platform options menu, which went with the bar.
     *
     * Only actions that belong to this screen. Navigation was removed from here: Map and
     * Modulation were reachable from the drawer and from the chip row as well, so the
     * same two destinations had three entry points while the overflow had no identity of
     * its own.
     */
    private void buildMenu() {
        header.setMenu(new String[]{
                "Reset traces", "Copy reading",
                MonitorService.running ? "Stop background" : "Run in background"
        }, new Header.MenuPick() {
            @Override public void picked(int id) {
                switch (id) {
                    case 0:
                        Trace.shared().clear();
                        Tracker.shared().clear();
                        for (Graph g : graphs) if (g != null) g.clear();
                        toast("Traces reset");
                        break;
                    case 1:
                        ClipboardManager cb = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        cb.setPrimaryClip(ClipData.newPlainText("reading", lastReport));
                        toast("Reading copied");
                        break;
                    case 2:
                        if (MonitorService.running) {
                            MonitorService.stop(MainActivity.this);
                            toast("Background off");
                        } else {
                            MonitorService.start(MainActivity.this);
                            toast("Running in background");
                        }
                        buildMenu();
                        break;
                }
            }
        });
    }
    private void info(String title, String message) {
        Sheet.info(this, title, message);
    }
    // ------------------------------------------------------------------ layout

    private View buildPage() {
        LinearLayout root = th.column(this);
        root.setBackgroundColor(skin.windowFlat());

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(Theme.grow());
        LinearLayout body = th.column(this);
        int p = th.dp((int) skin.padding);
        body.setPadding(p, th.dp(8), p, th.dp(10));
        // Without this the ScrollView jumps to the first focusable descendant on layout,
        // which is a chip well down the page, and the top quadrants are never seen.
        body.setFocusableInTouchMode(true);
        body.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.addView(body);

        body.addView(quadrantGrid());
        body.addView(servingPanel());
        body.addView(controls());
        body.addView(tabs());
        cellColumn = th.column(this);
        body.addView(cellColumn, Theme.wrap());

        root.addView(scroll);
        root.addView(statusBar());
        return root;
    }

    /**
     * The quadrant grid. Which readings appear, and whether they are drawn as traces or as
     * segmented meters, both come from the skin.
     */
    private View quadrantGrid() {
        LinearLayout grid = th.column(this);
        List<String> chans = skin.quadrants;
        int cols = Math.max(1, Math.min(2, skin.meterColumns));
        boolean asMeter = "vertical".equals(skin.meterStyle) || "flat".equals(skin.meterStyle);

        LinearLayout row = null;
        for (int i = 0; i < chans.size(); i++) {
            if (i % cols == 0) {
                row = th.row(this);
                LinearLayout.LayoutParams rlp = Theme.wrap();
                rlp.setMargins(0, 0, 0, th.dp((int) skin.sectionSpacing));
                grid.addView(row, rlp);
            }
            row.addView(quadrant(chans.get(i), asMeter));
        }
        return grid;
    }

    private View quadrant(String channel, boolean asMeter) {
        int accent = skin.accent(channel);

        LinearLayout box = th.column(this);
        box.setBackground(skin.tileBg(th));
        int pd = th.dp(7);
        box.setPadding(pd, pd, pd, pd);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(th.dp(2), 0, th.dp(2), 0);
        box.setLayoutParams(blp);

        LinearLayout head = th.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView cap = th.caption(this, labelFor(channel));
        cap.setTextColor(skin.inkSecondary);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(8.5f, skin.szLabel * 0.9f));
        head.addView(cap, Theme.weight(1f));

        TextView val = th.num(this, "--", skin.szValue, accent);
        val.setTypeface(skin.face(true, Typeface.BOLD));
        head.addView(val);
        box.addView(head, Theme.wrap());
        quadValues.add(val);
        quadChannels.add(channel);

        float[] rng = rangeFor(channel);

        if (asMeter) {
            Graph.Led led = new Graph.Led(this, th, skin, channel).range(rng[0], rng[1]);
            LinearLayout.LayoutParams lp = Theme.wrap();
            lp.height = th.dp((int) Math.max(60, skin.graphHeight));
            lp.setMargins(0, th.dp(5), 0, 0);
            box.addView(led, lp);
            leds.add(led);
            graphs.add(null);
        } else {
            Graph g = new Graph(this, th, skin, 600)
                    .channel(channel)
                    .range(rng[0], rng[1])
                    .grid(rng[2])
                    .unit("")
                    .labels(true)
                    .value(false)
                    .filled(true)
                    .note("collecting");
            LinearLayout.LayoutParams lp = Theme.wrap();
            lp.height = th.dp((int) Math.max(54, skin.graphHeight));
            lp.setMargins(0, th.dp(4), 0, 0);
            box.addView(g, lp);
            graphs.add(g);
            leds.add(null);
        }
        return box;
    }

    private static String labelFor(String channel) {
        if (Skin.CH_RSRP.equals(channel))  return "RSRP";
        if (Skin.CH_SINR.equals(channel))  return "SINR";
        if (Skin.CH_RSRQ.equals(channel))  return "RSRQ";
        if (Skin.CH_NEIGH.equals(channel)) return "Neighbours";
        if (Skin.CH_FREQ.equals(channel))  return "Downlink";
        if (Skin.CH_SAT.equals(channel))   return "Timing adv";
        if (Skin.CH_POWER.equals(channel)) return "Bandwidth";
        return channel;
    }

    /** low, high, gridline step. */
    private static float[] rangeFor(String channel) {
        if (Skin.CH_RSRP.equals(channel))  return new float[]{-135, -60, 25};
        if (Skin.CH_SINR.equals(channel))  return new float[]{-10, 35, 15};
        if (Skin.CH_RSRQ.equals(channel))  return new float[]{-25, -3, 10};
        if (Skin.CH_NEIGH.equals(channel)) return new float[]{0, 12, 4};
        if (Skin.CH_FREQ.equals(channel))  return new float[]{600, 4200, 1200};
        if (Skin.CH_SAT.equals(channel))   return new float[]{0, 60, 20};
        if (Skin.CH_POWER.equals(channel)) return new float[]{0, 110, 25};
        return new float[]{0, 100, 25};
    }

    private View servingPanel() {
        LinearLayout box = th.column(this);
        int pd = th.dp(10);
        box.setPadding(pd, th.dp(8), pd, th.dp(9));
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.setMargins(th.dp(2), 0, th.dp(2), th.dp((int) skin.sectionSpacing));
        box.setLayoutParams(lp);
        box.setBackground(th.ripple(skin.tileBg(th)));
        box.setClickable(true);

        TextView cap = th.caption(this, "Serving cell");
        cap.setTextColor(skin.accent(Skin.CH_RSRP));
        box.addView(cap);

        LinearLayout line = th.row(this);
        line.setGravity(Gravity.BOTTOM);
        line.setPadding(0, th.dp(2), 0, 0);

        bigValue = th.num(this, "--", 40, skin.inkPrimary);
        bigValue.setTypeface(skin.face(true, Typeface.BOLD));
        bigValue.setIncludeFontPadding(false);
        line.addView(bigValue);

        TextView unit = th.text(this, "dBm", 12, skin.inkSecondary);
        unit.setPadding(th.dp(5), 0, th.dp(12), th.dp(6));
        line.addView(unit);

        LinearLayout meterBox = th.column(this);
        meterBox.setPadding(0, 0, 0, th.dp(4));
        servingBars = new Graph.Led(this, th, skin, Skin.CH_RSRP)
                .range(-135, -60).horizontal();
        meterBox.addView(servingBars, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, th.dp(15)));
        servingLabel = th.num(this, "", 10, skin.inkSecondary);
        servingLabel.setPadding(0, th.dp(3), 0, 0);
        meterBox.addView(servingLabel);
        line.addView(meterBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        box.addView(line, Theme.wrap());

        // The figures that get used most while actually working: band, both
        // frequencies, bandwidth and RSRQ. They were buried in the detail line.
        FlowLayout stats = new FlowLayout(this).gap(th.dp(4));
        LinearLayout.LayoutParams flp = Theme.wrap();
        flp.setMargins(0, th.dp(9), 0, th.dp(2));
        stats.setLayoutParams(flp);
        statBand = bigStat(stats, "BAND");
        statDl   = bigStat(stats, "DL MHz");
        statUl   = bigStat(stats, "UL MHz");
        statBw   = bigStat(stats, "BW MHz");
        statRsrq = bigStat(stats, "RSRQ dB");
        box.addView(stats);

        servingIdent = th.num(this, "", 11, skin.inkSecondary);
        servingIdent.setPadding(0, th.dp(7), 0, 0);
        box.addView(servingIdent);

        servingRf = th.num(this, "", 10, skin.inkSecondary);
        servingRf.setPadding(0, th.dp(3), 0, 0);
        box.addView(servingRf);

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ModemActivity.class));
            }
        });
        return box;
    }

    private View controls() {
        // Wrapping, not scrolling: a shortcut you cannot see is a shortcut you forget.
        FlowLayout row = new FlowLayout(this).gap(th.dp(5));
        row.setPadding(th.dp(2), 0, th.dp(2), th.dp(7));

        row.addView(chip("*#0011#", "service mode", new Runnable() {
            @Override public void run() { dialer("*#0011"); }
        }));
        row.addView(chip("*#2263#", "band select", new Runnable() {
            @Override public void run() { dialer("*#2263"); }
        }));
        row.addView(chip("Network", "operator select", new Runnable() {
            @Override public void run() { openNetworkPicker(); }
        }));
        // Navigation deliberately does not live here. Map, Modulation and Detail were
        // chips as well as drawer entries, and because they had no subtitle the row
        // rendered at two different heights. The drawer reaches them and now shows
        // which screen you are on, so this row is only things that act on the radio.
        return row;
    }

    /** Caption over a value, sized to be readable at arm's length on a ladder. */
    private TextView bigStat(FlowLayout into, String caption) {
        LinearLayout col = th.column(this);
        col.setPadding(th.dp(9), th.dp(5), th.dp(11), th.dp(6));
        col.setBackground(skin.badgeBg(th, Skin.alpha(skin.inkSecondary, 0.07f)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, th.dp(4), 0);
        col.setLayoutParams(lp);

        TextView cap = th.caption(this, caption);
        cap.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8.5f);
        col.addView(cap);

        TextView val = th.num(this, "--", 17, skin.inkPrimary);
        val.setTypeface(skin.face(true, Typeface.BOLD));
        val.setPadding(0, th.dp(1), 0, 0);
        col.addView(val);

        into.addView(col);
        return val;
    }

    /** RSRQ sits on its own scale: better than -10 dB is healthy, below -15 is poor. */
    private int gradeRsrq(int rsrq) {
        if (!Sampler.has(rsrq)) return skin.inkSecondary;
        if (rsrq >= -10) return skin.accent(Skin.CH_FREQ);
        if (rsrq >= -15) return skin.accent(Skin.CH_SAT);
        return skin.accent(Skin.CH_ALERT);
    }
    private View chip(String label, String hint, final Runnable action) {
        LinearLayout c = th.column(this);
        c.setBackground(th.ripple(
                skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f))));
        c.setPadding(th.dp(11), th.dp(6), th.dp(11), th.dp(6));
        c.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, th.dp(6), 0);
        c.setLayoutParams(lp);

        TextView t = th.num(this, label, 12, skin.accent(Skin.CH_RSRP));
        t.setTypeface(skin.face(true, Typeface.BOLD));
        c.addView(t);
        if (hint != null) c.addView(th.text(this, hint, 9, skin.inkSecondary));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return c;
    }

    private View tabs() {
        tabStrip = new FlowLayout(this).gap(th.dp(5));
        tabStrip.setPadding(th.dp(2), 0, th.dp(2), th.dp(2));
        return tabStrip;
    }

    private View statusBar() {
        LinearLayout strip = th.row(this);
        strip.setBackgroundColor(skin.header[0]);
        strip.setPadding(th.dp(12), th.dp(6), th.dp(12), th.dp(7));
        strip.setGravity(Gravity.CENTER_VERTICAL);

        statusLeft = th.num(this, "", 10, skin.inkSecondary);
        strip.addView(statusLeft, Theme.weight(1f));

        statusRight = th.num(this, "", 10, skin.inkSecondary);
        statusRight.setGravity(Gravity.END);
        strip.addView(statusRight);

        LinearLayout wrap = th.column(this);
        View rule = new View(this);
        rule.setBackgroundColor(skin.headerRule);
        wrap.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair())));
        wrap.addView(strip, Theme.wrap());
        return wrap;
    }

    // ------------------------------------------------------------------ refresh

    private void tick() {
        if (!running) return;
        refresh();
        handler.postDelayed(new Runnable() { @Override public void run() { tick(); } },
                Prefs.refresh(this));
    }

    private void refresh() {
        // The cell list tags its own component carriers, so the component list has to
        // be kept warm here too — not only on the Modulation screen, which a person
        // may never open. Returns immediately unless a read is actually due.
        AggFeed.refresh(this);

        if (!granted()) {
            header.setSubtitle("permissions needed");
            cellColumn.removeAllViews();
            cellColumn.addView(note("Grant phone state and precise location, then reopen.\n\n"
                    + "Android gates cell identity behind precise location. Nothing is "
                    + "uploaded; the only network call this app makes is for map tiles."));
            return;
        }

        Sampler.Snap snap = Tracker.step(this, sampler);
        Tracker tracker = Tracker.shared();

        header.setSubtitle(snap.title() + (snap.roaming ? "  ·  roaming" : ""));
        if (drawer != null) {
            drawer.setStatus(snap.title() + "   ·   "
                    + (Sampler.has(snap.dbm()) ? snap.dbm() + " dBm" : "no signal"));
        }

        pushQuadrants(snap);
        paintServing(snap);
        paintCells(tracker, snap);
        paintStatus(tracker, snap);
    }

    private void pushQuadrants(Sampler.Snap snap) {
        // Fold every scan into the cumulative session list, so the DAS log is
        // complete whether or not that screen was ever opened.
        try { SessionLog.shared().add(snap); } catch (Throwable ignored) { }
        Sampler.Cell s = snap.serving;
        for (int i = 0; i < quadChannels.size(); i++) {
            String ch = quadChannels.get(i);
            float v = Float.NaN;
            String txt = "--";

            if (Skin.CH_RSRP.equals(ch) && s != null && Sampler.has(s.dbm)) {
                v = s.dbm; txt = String.valueOf(s.dbm);
            } else if (Skin.CH_SINR.equals(ch) && s != null && Sampler.has(s.qual)) {
                v = s.qual; txt = String.valueOf(s.qual);
            } else if (Skin.CH_RSRQ.equals(ch) && s != null && Sampler.has(s.rsrq)) {
                v = s.rsrq; txt = String.valueOf(s.rsrq);
            } else if (Skin.CH_NEIGH.equals(ch)) {
                v = snap.neighbours(); txt = String.valueOf(snap.neighbours());
            } else if (Skin.CH_FREQ.equals(ch) && s != null && !Double.isNaN(s.dlMhz)) {
                v = (float) s.dlMhz; txt = Rf.mhz(s.dlMhz);
            } else if (Skin.CH_SAT.equals(ch) && s != null && Sampler.has(s.ta)) {
                v = s.ta; txt = Math.round(s.ta * Locate.TA_METRES) + " m";
            } else if (Skin.CH_POWER.equals(ch) && s != null && Sampler.has(s.bwKhz)) {
                v = s.bwKhz / 1000f; txt = (s.bwKhz / 1000) + " MHz";
            }

            quadValues.get(i).setText(txt);
            Graph g = graphs.get(i);
            if (g != null) { if (Float.isNaN(v)) g.pushGap(); else g.push(v); }
            Graph.Led l = leds.get(i);
            if (l != null) l.set(v);
        }
    }

    private void paintServing(Sampler.Snap snap) {
        Sampler.Cell s = snap.serving;
        StringBuilder report = new StringBuilder();
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        report.append(snap.title()).append("  @").append(stamp).append('\n');

        if (s == null || !Sampler.has(s.dbm)) {
            bigValue.setText("--");
            bigValue.setTextColor(skin.inkSecondary);
            servingLabel.setText("no serving signal");
            servingIdent.setText(snap.cells.isEmpty()
                    ? "no cells reported — on satellite, or the radio is idle"
                    : "registered cell is not reporting a level");
            servingRf.setText("");
            statBand.setText("--"); statDl.setText("--"); statUl.setText("--");
            statBw.setText("--");   statRsrq.setText("--");
            servingBars.set(Float.NaN);
            lastReport = report.toString();
            return;
        }

        Trace trace = Trace.shared();
        bigValue.setText(String.valueOf(s.dbm));
        bigValue.setTextColor(th.grade(s.dbm));
        servingBars.set(s.dbm);

        StringBuilder lab = new StringBuilder(Trace.label(s.dbm));
        if (Sampler.has(trace.peak())) lab.append("   pk ").append(trace.peak());
        if (Sampler.has(trace.avg()))  lab.append("   avg ").append(trace.avg());
        lab.append("   n=").append(trace.samples());
        servingLabel.setText(lab.toString());

        StringBuilder id = new StringBuilder(s.tech);
        if (s.carrier != null) id.append("   ").append(s.carrier);
        if (s.detail != null && !s.detail.isEmpty()) id.append("   ").append(s.detail);
        servingIdent.setText(id.toString());

        // The five headline figures.
        statBand.setText(Sampler.has(s.band)
                ? ("NR".equals(s.tech) ? "n" : "B") + s.band + (Rf.isTdd(s.band) ? " TDD" : "")
                : "--");
        statBand.setTextColor(skin.inkPrimary);

        statDl.setText(Double.isNaN(s.dlMhz) ? "--" : Rf.mhz(s.dlMhz).replace(" MHz", ""));
        statDl.setTextColor(skin.accent(Skin.CH_RSRP));

        // On TDD the uplink shares the downlink frequency rather than pairing with it.
        statUl.setText(Double.isNaN(s.ulMhz) ? "--" : Rf.mhz(s.ulMhz).replace(" MHz", ""));
        statUl.setTextColor(Sampler.has(s.band) && Rf.isTdd(s.band)
                ? skin.inkSecondary : skin.accent(Skin.CH_SINR));

        String bwText = Rf.bw(s.bwKhz, Integer.MAX_VALUE);
        statBw.setText("-".equals(bwText) ? "--" : bwText.replace(" MHz", ""));
        statBw.setTextColor("-".equals(bwText) ? skin.inkSecondary : skin.inkPrimary);

        statRsrq.setText(Sampler.has(s.rsrq) ? String.valueOf(s.rsrq) : "--");
        statRsrq.setTextColor(gradeRsrq(s.rsrq));

        // Whatever did not earn a tile stays on the detail line.
        StringBuilder rf = new StringBuilder();
        if (Sampler.has(s.arfcn)) rf.append("ARFCN ").append(s.arfcn).append("   ");
        if (Sampler.has(s.ta)) {
            rf.append("TA ").append(s.ta).append(" (~")
              .append(Math.round(s.ta * Locate.TA_METRES)).append(" m)   ");
        }
        Mcs.Entry e = Sampler.has(s.cqi)
                ? Mcs.forCqi(s.cqi, Sampler.has(s.cqiTable) ? s.cqiTable : 1) : null;
        if (e != null) rf.append("CQI ").append(s.cqi).append(" ").append(e.modulation);
        servingRf.setText(rf.toString().trim());

        report.append(id).append('\n').append(rf).append('\n');
        lastReport = report.toString();
    }

    private void paintCells(Tracker tracker, Sampler.Snap snap) {
        cellColumn.removeAllViews();
        long now = snap.ts;
        long linger = Prefs.linger(this);

        List<Tracker.Track> all = tracker.all();
        List<Tracker.Track> shown = new ArrayList<>();
        for (Tracker.Track t : all) {
            if (t.last == null) continue;
            String carrier = t.last.carrier;
            if (TAB_ALL.equals(selectedTab)) shown.add(t);
            else if (TAB_UNKNOWN.equals(selectedTab)) { if (carrier == null) shown.add(t); }
            else if (selectedTab.equals(carrier)) shown.add(t);
        }

        carriersBlock(tracker);

        cellColumn.addView(th.heading(this, "Cells  ·  " + shown.size()
                + (shown.size() != all.size() ? " of " + all.size() : "")
                + (linger > 0 ? "  ·  " + Prefs.lingerLabel(this) + " memory" : "")));

        if (shown.isEmpty()) {
            cellColumn.addView(note(all.isEmpty()
                    ? "No cells reported yet."
                    : "No cells on this tab right now."));
            return;
        }
        for (Tracker.Track t : shown) cellColumn.addView(cellRow(t, now, linger));
    }

    /**
     * The component carriers, listed in their own right rather than looked for in the
     * cell list.
     *
     * They cannot be left to the cell list, which was the first attempt. A secondary
     * carrier only appears in getAllCellInfo() while data is actually moving, and it
     * drops out within a second or two of the transfer ending — while the physical
     * channel configuration still reports the aggregation. So the two genuinely
     * disagree, and tagging cells could only ever label the carriers that happened to
     * be listed at that instant. That is why the Modulation screen could show 3CC while
     * the cell list showed one cell: both were right.
     *
     * Shown only when actually aggregating. One carrier is the normal case and needs no
     * announcement.
     */
    private void carriersBlock(Tracker tracker) {
        java.util.List<Agg.Cc> ccs = Agg.components(this);
        if (ccs.size() < 2) return;

        int total = Agg.totalBandwidthKhz(this);
        cellColumn.addView(th.heading(this, "Carriers  ·  " + ccs.size() + "CC"
                + (total > 0 ? "  ·  " + (total / 1000) + " MHz" : "")));

        LinearLayout box = th.column(this);
        box.setBackground(skin.tileBg(th));
        int p = th.dp(10);
        box.setPadding(p, th.dp(8), p, th.dp(8));

        for (Agg.Cc cc : ccs) {
            LinearLayout r = th.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, th.dp(3), 0, th.dp(3));

            TextView tag = th.num(this, cc.primary ? "PCC" : "SCC", 9.5f, skin.windowFlat());
            tag.setTypeface(skin.face(true, Typeface.BOLD));
            tag.setPadding(th.dp(5), th.dp(2), th.dp(5), th.dp(2));
            tag.setBackground(th.solid(
                    skin.accent(cc.primary ? Skin.CH_RSRP : Skin.CH_SINR),
                    skin.badgeCornerRadius));
            r.addView(tag, Theme.fixed(ViewGroup.LayoutParams.WRAP_CONTENT,
                                       ViewGroup.LayoutParams.WRAP_CONTENT));

            StringBuilder d = new StringBuilder();
            d.append("  ").append(cc.bandLabel()).append("   ").append(cc.bwLabel());
            if (cc.pci >= 0) d.append("   PCI ").append(cc.pci);
            TextView line = th.num(this, d.toString(), 10, skin.inkSecondary);
            r.addView(line, Theme.weight(1f));

            // When this carrier is also in the cell list, its own level is worth having:
            // a secondary that is 15 dB down is carrying far less than the count implies.
            Integer dbm = levelFor(tracker, cc);
            if (dbm != null) {
                TextView v = th.num(this, String.valueOf(dbm), 11, th.grade(dbm));
                v.setTypeface(skin.face(true, Typeface.BOLD));
                r.addView(v);
            }
            box.addView(r, Theme.wrap());
        }
        cellColumn.addView(box, Theme.wrap());
    }

    /** The measured level of a component carrier, if the cell list happens to have it. */
    private Integer levelFor(Tracker tracker, Agg.Cc cc) {
        if (cc.pci < 0) return null;
        for (Tracker.Track t : tracker.all()) {
            Sampler.Cell c = t.last;
            if (c == null || !Sampler.has(c.pci) || c.pci != cc.pci) continue;
            if (cc.band > 0 && Sampler.has(c.band) && c.band != cc.band) continue;
            if (Sampler.has(c.dbm)) return c.dbm;
        }
        return null;
    }

    /**
     * One cell. Serving cells carry the accent; cells that have dropped out of the current
     * sweep fade toward the end of the linger window and show how long ago they went.
     */
    private View cellRow(final Tracker.Track t, long now, long linger) {
        final Sampler.Cell c = t.last;
        boolean stale = t.stale(now);
        float opacity = stale ? Math.max(0.30f, t.freshness(now, linger)) : 1f;

        LinearLayout row = th.column(this);
        row.setBackground(th.ripple(skin.tileBg(th)));
        row.setClickable(true);
        int pd = th.dp(9);
        row.setPadding(pd, th.dp(7), pd, th.dp(8));
        LinearLayout.LayoutParams rlp = Theme.wrap();
        rlp.setMargins(th.dp(2), 0, th.dp(2), th.dp(3));
        row.setLayoutParams(rlp);
        row.setAlpha(opacity);

        LinearLayout top = th.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = th.num(this, c.tech, 9.5f, skin.windowFlat());
        badge.setTypeface(skin.face(true, Typeface.BOLD));
        badge.setPadding(th.dp(6), th.dp(2), th.dp(6), th.dp(2));
        badge.setBackground(th.solid(th.techColour(c.tech), skin.badgeCornerRadius));
        top.addView(badge);

        // A secondary carrier is listed here like any other nearby cell, and looks like
        // a neighbour unless it is named. Tagging them turns the list from "four cells
        // in range" into "two of these are carrying my data". Only appears when the
        // component carriers are known, which on most phones needs the privileged
        // backend — see Agg.
        String caRole = Agg.role(this, c.pci, c.band);
        if (caRole != null) {
            TextView ca = th.num(this, caRole, 9.5f, skin.windowFlat());
            ca.setTypeface(skin.face(true, Typeface.BOLD));
            ca.setPadding(th.dp(5), th.dp(2), th.dp(5), th.dp(2));
            ca.setBackground(th.solid(
                    skin.accent("PCC".equals(caRole) ? Skin.CH_RSRP : Skin.CH_SINR),
                    skin.badgeCornerRadius));
            LinearLayout.LayoutParams clp = Theme.wrap();
            clp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            clp.setMargins(th.dp(5), 0, 0, 0);
            top.addView(ca, clp);
        }

        TextView who = th.text(this,
                (c.carrier != null ? c.carrier : "unattributed")
                        + (c.serving ? "  ·  registered" : ""),
                11, c.serving ? skin.accent(Skin.CH_RSRP) : skin.inkSecondary);
        who.setPadding(th.dp(7), 0, th.dp(6), 0);
        who.setTypeface(skin.face(false, c.serving ? Typeface.BOLD : Typeface.NORMAL));
        top.addView(who, Theme.weight(1f));

        if (stale) {
            TextView age = th.num(this, "held " + fmtAge(now - t.lastSeen), 9.5f,
                    skin.inkSecondary);
            age.setPadding(0, 0, th.dp(7), 0);
            top.addView(age);
        } else {
            int tr = t.trend();
            if (tr != 0) {
                TextView arrow = th.num(this, tr > 0 ? "up" : "dn", 9.5f,
                        tr > 0 ? skin.accent(Skin.CH_FREQ) : skin.accent(Skin.CH_ALERT));
                arrow.setPadding(0, 0, th.dp(7), 0);
                top.addView(arrow);
            }
        }

        TextView val = th.num(this, Sampler.has(c.dbm) ? String.valueOf(c.dbm) : "--",
                15, th.grade(c.dbm));
        val.setTypeface(skin.face(true, Typeface.BOLD));
        top.addView(val);
        row.addView(top, Theme.wrap());

        LinearLayout mid = th.row(this);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.setPadding(0, th.dp(5), 0, 0);

        Graph.Spark spark = new Graph.Spark(this, th);
        spark.range(-135, -60);
        spark.set(t.values(), th.grade(c.dbm), opacity);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(th.dp(74), th.dp(22));
        slp.setMargins(0, 0, th.dp(8), 0);
        mid.addView(spark, slp);

        StringBuilder d = new StringBuilder();
        if (Sampler.has(c.band)) {
            d.append("NR".equals(c.tech) ? "n" : "B").append(c.band).append("  ");
        }
        if (!Double.isNaN(c.dlMhz)) d.append(Rf.mhz(c.dlMhz)).append("  ");
        if (Sampler.has(c.pci)) d.append("PCI ").append(c.pci).append("  ");
        if (Sampler.has(c.arfcn)) d.append("#").append(c.arfcn).append("  ");
        if (Sampler.has(c.qual)) d.append("SINR ").append(c.qual);
        mid.addView(th.num(this, d.toString().trim(), 10, skin.inkSecondary),
                Theme.weight(1f));
        row.addView(mid, Theme.wrap());

        StringBuilder hist = new StringBuilder();
        hist.append(t.sightings).append(t.sightings == 1 ? " sighting" : " sightings");
        if (Sampler.has(t.best))      hist.append("   best ").append(t.best);
        if (Sampler.has(t.average())) hist.append("   mean ").append(t.average());
        if (t.first > 0)              hist.append("   over ").append(fmtAge(now - t.first));
        TextView h3 = th.num(this, hist.toString(), 9,
                Skin.alpha(skin.inkSecondary, 0.85f));
        h3.setPadding(0, th.dp(3), 0, 0);
        row.addView(h3);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, CellActivity.class);
                i.putExtra("ident", t.ident);
                startActivity(i);
            }
        });
        return row;
    }

    private void paintStatus(Tracker tracker, Sampler.Snap snap) {
        boolean rec = Prefs.bool(this, Prefs.WARDRIVE_ON, false);
        StringBuilder l = new StringBuilder();
        l.append(tracker.liveCount()).append(" live");
        if (tracker.lingeringCount() > 0) {
            l.append("   ").append(tracker.lingeringCount()).append(" held");
        }
        l.append("   ").append(MonitorService.running ? "svc on" : "svc off");
        statusLeft.setText(l.toString());

        StringBuilder r = new StringBuilder();
        r.append(rec ? "REC" : "idle");
        android.location.Location fix = MonitorService.lastFix();
        if (fix != null) r.append("   ").append(Math.round(fix.getAccuracy())).append(" m");
        r.append("   ").append(new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date(snap.ts)));
        statusRight.setText(r.toString());
        statusRight.setTextColor(rec ? skin.accent(Skin.CH_ALERT) : skin.inkSecondary);
    }

    // ------------------------------------------------------------------ tabs

    private void rebuildTabs() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();

        List<String> names = new ArrayList<>();
        names.add(TAB_ALL);
        names.addAll(sampler.knownCarriers());
        names.add(TAB_UNKNOWN);
        if (!names.contains(selectedTab)) selectedTab = TAB_ALL;

        for (final String name : names) {
            boolean on = name.equals(selectedTab);
            TextView t = th.text(this, name, 12, on ? skin.windowFlat() : skin.inkSecondary);
            t.setTypeface(skin.face(false, on ? Typeface.BOLD : Typeface.NORMAL));
            t.setPadding(th.dp(12), th.dp(5), th.dp(12), th.dp(5));
            t.setBackground(on
                    ? th.solid(skin.accent(Skin.CH_RSRP), skin.badgeCornerRadius)
                    : skin.badgeBg(th, 0x00000000));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, th.dp(5), 0);
            t.setLayoutParams(lp);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedTab = name;
                    rebuildTabs();
                    refresh();
                }
            });
            tabStrip.addView(t);
        }
    }

    // ------------------------------------------------------------------ helpers

    static String fmtAge(long ms) {
        if (ms < 1000) return "0s";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 48) return h + "h";
        return (h / 24) + "d";
    }

    private TextView note(String s) {
        TextView t = th.text(this, s, 12, skin.inkSecondary);
        t.setPadding(th.dp(6), th.dp(12), th.dp(6), th.dp(12));
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private boolean granted() {
        return Perms.core(this);
    }

    /**
     * On a first run, hand over to the welcome page instead of firing the platform's
     * permission prompts at someone who has not been told what the app is. It sets the
     * pref itself and comes back here.
     *
     * @return true if the welcome page took over and this screen should stop setting up.
     */
    private boolean firstRun() {
        if (Prefs.bool(this, Prefs.ONBOARDED, false)) return false;
        startActivity(new Intent(this, WelcomeActivity.class));
        return true;
    }

    private void ensurePermissions() {
        Perms.request(this, REQ);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) { refresh(); }

    /**
     * Open the dialer pre-filled with a keystring minus its final '#'. Samsung fires the
     * code from the dialpad keypress itself, so that last character has to be the user's.
     */
    /**
     * The platform's own operator picker — manual network search and selection.
     *
     * Tried in order of how specific the screen is, because OEMs move it around and the
     * narrower intents are not present on every build. resolveActivity is deliberately
     * not used: package visibility rules on API 30 and up can hide Settings from the
     * query even though the intent would start perfectly well, so this just attempts
     * each one and falls through on the exception.
     */
    private void openNetworkPicker() {
        String[] actions = {
                android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
                android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS,
                android.provider.Settings.ACTION_WIRELESS_SETTINGS,
        };
        for (String action : actions) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (Exception ignored) {
                // Not on this build; try the next one up.
            }
        }
        toast("No network settings screen on this build");
    }

    private void dialer(String partial) {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(partial))));
            toast("Press # on the dialpad");
        } catch (Exception e) {
            toast("No dialer available");
        }
    }
}

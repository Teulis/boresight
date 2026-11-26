package com.boresight;

import android.content.Context;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The driving view: what you need while the phone is on a mount and you are not.
 *
 * Deliberately not a dashboard. Everything here is sized to be read in a glance from
 * arm's length with the car moving, which means a small number of very large numbers
 * and no scrolling, rather than the dense panes the other screens use. The constraint
 * that drives the layout is that any row needing a second look is a row that does not
 * belong on this screen.
 *
 * It also never asks for a tap to stay useful: the screen is held awake, recording can
 * be toggled with one large target, and nothing here opens a dialog while moving.
 */
public class HudActivity extends Screen {

    private static final long TICK_MS = 1000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Sampler sampler;
    private LocationManager lm;
    private Location fix;
    private boolean live;

    private TextView carrierLine, identBig, techBadge;
    private TextView vSpeed, vAlt, vSignal, vBearing, vRec, vHits;
    private TextView nearName, nearRange, nearBear, nearNote;
    private TextView footer, sinceLine;
    private View recBox;
    private TextView bigButton;
    private TileMapView miniMap;
    private TextView mapHint;
    private long mapPainted;

    /** Cached so the nearest-detection search is not redone from the database each second. */
    private List<Store.Hit> hits;
    private long hitsLoaded;

    @Override protected String title() { return "DRIVING"; }

    @Override protected void content() {
        sampler = new Sampler(this);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (Prefs.bool(this, Prefs.HUD_KEEP_AWAKE, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // ---------------------------------------------------------------- readout
        //
        // Laid out after CellMapper's driving view, which gets the priorities right:
        // one identity line that says exactly which radio you are on, then a small
        // number of labelled values large enough to read at a glance, and nothing
        // else competing for attention. The detections column is ours — the whole
        // point of driving with this is what it finds, so it sits beside the radio
        // figures rather than below them.
        LinearLayout head = pane(null);

        LinearLayout idRow = th.row(this);
        LinearLayout idLeft = th.column(this);
        carrierLine = th.text(this, "no service", 12, skin.inkSecondary);
        idLeft.addView(carrierLine);
        identBig = th.num(this, "--", 30, skin.inkPrimary);
        identBig.setTypeface(skin.face(true, Typeface.BOLD));
        idLeft.addView(identBig);
        idRow.addView(idLeft, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        techBadge = th.num(this, "", 14, skin.accent(Skin.CH_RSRP));
        techBadge.setTypeface(skin.face(true, Typeface.BOLD));
        techBadge.setGravity(Gravity.END);
        idRow.addView(techBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(idRow, Theme.wrap());

        // Two rows of three. Three across stays readable down to a half-width
        // split-screen pane, where four would start truncating the numbers.
        LinearLayout r1 = th.row(this);
        vSpeed  = cell(r1, "SPEED");
        vAlt    = cell(r1, "ALTITUDE");
        vSignal = cell(r1, "SIGNAL");
        head.addView(r1, Theme.wrap());

        LinearLayout r2 = th.row(this);
        vBearing = cell(r2, "BEARING");
        vRec     = cell(r2, "RECORDING");
        vHits    = cell(r2, "DETECTIONS");
        head.addView(r2, Theme.wrap());

        // The two that are also controls. Tapping a number that looks like a readout
        // is not discoverable, so the captions say so.
        ((View) vRec.getParent()).setClickable(true);
        ((View) vRec.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecording(); }
        });
        ((View) vHits.getParent()).setClickable(true);
        ((View) vHits.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(HudActivity.this, FlockActivity.class));
            }
        });

        footer = th.text(this, "waiting for a fix", 11, skin.inkSecondary);
        footer.setGravity(Gravity.CENTER);
        head.addView(footer);

        // One unmissable target. The RECORDING tile already toggles, but a tile that
        // happens to be tappable is not a control you reach for at 45 mph without
        // looking — and the cost of guessing wrong is a drive that recorded nothing.
        bigButton = th.num(this, "START RECORDING", 20, skin.windowFlat());
        bigButton.setTypeface(skin.face(true, Typeface.BOLD));
        bigButton.setGravity(Gravity.CENTER);
        bigButton.setPadding(th.dp(16), th.dp(18), th.dp(16), th.dp(18));
        bigButton.setClickable(true);
        bigButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecording(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(th.dp(2), th.dp(6), th.dp(2), th.dp(2));
        body.addView(bigButton, bp);

        // --- nearest detection -----------------------------------------------
        LinearLayout near = pane("Nearest detection");
        nearName = big(near, "none yet", 22);
        sinceLine = th.text(this, "", 12, skin.accent(Skin.CH_SAT));
        near.addView(sinceLine);
        LinearLayout nrow = th.row(this);
        nearRange = th.num(this, "--", 28, skin.accent(Skin.CH_ALERT));
        nearRange.setTypeface(skin.face(true, Typeface.BOLD));
        nrow.addView(nearRange);
        nearBear = th.num(this, "", 16, skin.inkSecondary);
        nearBear.setPadding(th.dp(12), th.dp(8), 0, 0);
        nrow.addView(nearBear);
        near.addView(nrow, Theme.wrap());
        nearNote = hint(near, "Range is straight-line from your fix to where the "
                + "detection was logged, not road distance — and it is where the "
                + "camera was seen, which for a fixed installation is where it still is.");

        // --- the live map ----------------------------------------------------
        //
        // Not Google Maps, and not for want of trying: a third-party app cannot embed
        // Google's turn-by-turn view. Android Auto's navigation surface is reserved for
        // apps in approved categories, and the Maps SDK draws a map but not Google's
        // navigation. What it would cost is an API key, a Cloud project tied to the
        // signing certificate, and a hard dependency on Play Services, in exchange for
        // a worse map than this one for this purpose.
        //
        // This is the app's own tile view, which already knows how to draw the track,
        // the detections and the known cameras, works from cached tiles with no signal,
        // and needs no key. Google Maps stays one tap away for actual navigation.
        LinearLayout mapPane = pane("Map");
        miniMap = new TileMapView(this, th, skin);
        miniMap.setTiles(Prefs.bool(this, Prefs.L_TILES, true));
        miniMap.setDim(true);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, th.dp(220));
        mapPane.addView(miniMap, mp);
        mapHint = hint(mapPane, "Follows your position. Rings are cameras already on "
                + "the map; filled dots are what this phone detected itself.");
        LinearLayout maprow = actionRow(mapPane);
        smallAction(maprow, "Full map", new Runnable() {
            @Override public void run() {
                startActivity(new android.content.Intent(HudActivity.this, MapActivity.class));
            }
        });
        smallAction(maprow, "Open Maps", new Runnable() {
            @Override public void run() { openMaps(); }
        });

        LinearLayout row = actionRow(body);
        smallAction(row, "Units", new Runnable() {
            @Override public void run() {
                boolean now = !Prefs.bool(HudActivity.this, Prefs.HUD_IMPERIAL, true);
                Prefs.put(HudActivity.this, Prefs.HUD_IMPERIAL, now);
                toast(now ? "mph / feet" : "km/h / metres");
                tick();
            }
        });
        smallAction(row, "Map", new Runnable() {
            @Override public void run() {
                startActivity(new android.content.Intent(HudActivity.this, MapActivity.class));
            }
        });
        smallAction(row, "Sync", new Runnable() {
            @Override public void run() {
                startActivity(new android.content.Intent(HudActivity.this, SyncActivity.class));
            }
        });
    }

    /**
     * One labelled value in the readout grid: small caption, large number.
     *
     * Returns the value view; its parent is the tappable column, which is how the two
     * cells that double as controls get their click target without the caption and the
     * number needing separate handling.
     */
    private TextView cell(LinearLayout into, String caption) {
        LinearLayout col = th.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(th.dp(4), th.dp(10), th.dp(4), th.dp(10));

        TextView cap = th.caption(this, caption);
        cap.setGravity(Gravity.CENTER);
        col.addView(cap);

        TextView v = th.num(this, "--", 26, skin.inkPrimary);
        v.setTypeface(skin.face(true, Typeface.BOLD));
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        col.addView(v);

        // Equal thirds. Zero base width with weight, so a long value shrinks its own
        // text rather than pushing its neighbours off the row.
        into.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    /** A big number with a caption above and a note below, sized for a glance. */
    private View statTile(LinearLayout into, String caption) {
        LinearLayout col = th.column(this);
        col.setBackground(skin.tileBg(th));
        col.setPadding(th.dp(12), th.dp(9), th.dp(12), th.dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(th.dp(2), th.dp(3), th.dp(2), th.dp(3));
        col.setLayoutParams(lp);

        col.addView(th.caption(this, caption));
        TextView v = th.num(this, "--", 30, skin.inkPrimary);
        v.setTypeface(skin.face(true, Typeface.BOLD));
        col.addView(v);
        col.addView(th.text(this, "", 10, skin.inkSecondary));
        into.addView(col);
        return col;
    }

    private TextView huge(LinearLayout into, String s, int sp) {
        TextView t = th.num(this, s, sp, skin.accent(Skin.CH_RSRP));
        t.setTypeface(skin.face(true, Typeface.BOLD));
        into.addView(t);
        return t;
    }

    private TextView big(LinearLayout into, String s, int sp) {
        TextView t = th.num(this, s, sp, skin.inkPrimary);
        t.setTypeface(skin.face(true, Typeface.BOLD));
        into.addView(t);
        return t;
    }

    // ------------------------------------------------------------------ data

    @Override protected void onResume() {
        super.onResume();
        live = true;
        startLocation();
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        live = false;
        try { lm.removeUpdates(locListener); } catch (Throwable ignored) { }
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { fix = l; }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
    };

    private void startLocation() {
        try {
            // GPS directly rather than the fused provider: this screen is about speed
            // and heading while moving, and GPS gives both from the Doppler solution
            // without the smoothing the fused provider applies.
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locListener);
            if (fix == null) fix = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
            footer.setText("location permission not granted");
        } catch (Throwable ignored) { }
        // The recorder already holds a fix when it is running; no reason to wait for
        // our own first one.
        if (fix == null) fix = MonitorService.lastFix();
    }

    private void tick() {
        if (!live) return;
        boolean imperial = Prefs.bool(this, Prefs.HUD_IMPERIAL, true);

        // speed, altitude, bearing
        if (fix != null && fix.hasSpeed()) {
            double v = fix.getSpeed() * (imperial ? 2.236936 : 3.6);
            vSpeed.setText(Math.round(v) + (imperial ? " mph" : " km/h"));
        } else {
            vSpeed.setText("--");
        }

        if (fix != null && fix.hasAltitude()) {
            double a = fix.getAltitude() * (imperial ? 3.280840 : 1.0);
            vAlt.setText(Math.round(a) + (imperial ? " ft" : " m"));
        } else {
            vAlt.setText("--");
        }

        // Bearing only while actually moving. A GPS heading at a standstill is noise,
        // and a confident compass point that swings at every stop light is worse than
        // a dash.
        if (fix != null && fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() > 1.5f) {
            vBearing.setText(compass(fix.getBearing())
                    + "  " + Math.round(fix.getBearing()) + "°");
        } else {
            vBearing.setText("--");
        }

        if (fix == null) {
            footer.setText("no fix yet");
        } else {
            long age = (System.currentTimeMillis() - fix.getTime()) / 1000;
            footer.setText(String.format(java.util.Locale.US,
                    "%.5f, %.5f   ·   ±%.0f m   ·   %s",
                    fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(),
                    age < 5 ? "live" : age + " s old"));
        }

        // serving cell
        Sampler.Snap snap = sampler.sample();
        Sampler.Cell s = snap == null ? null : snap.serving;
        if (s == null) {
            carrierLine.setText("no service");
            identBig.setText("--");
            techBadge.setText("");
            vSignal.setText("--");
        } else {
            StringBuilder c = new StringBuilder();
            c.append(s.carrier == null ? "unknown carrier" : s.carrier);
            if (s.mcc != null) c.append(" (").append(s.mcc).append(")");
            carrierLine.setText(c.toString());

            identBig.setText(siteLabel(s));

            // NR reports whether it is standalone; for everything else the technology
            // name is the honest badge.
            techBadge.setText("NR".equals(s.tech)
                    ? ("NR".equals(snap.netType) ? "SA" : "NSA")
                    : s.tech);

            vSignal.setText(Sampler.has(s.dbm) ? s.dbm + " dBm" : "--");
            vSignal.setTextColor(gradeDbm(s.dbm));
        }

        // recording
        boolean rec = MonitorService.recording();
        vRec.setText(rec ? String.valueOf(MonitorService.loggedCount()) : "off");
        vRec.setTextColor(rec ? skin.accent(Skin.CH_FREQ) : skin.inkSecondary);

        if (bigButton != null) {
            bigButton.setText(rec ? "STOP RECORDING" : "START RECORDING");
            bigButton.setBackground(skin.badgeBg(th,
                    rec ? skin.accent(Skin.CH_ALERT) : skin.accent(Skin.CH_FREQ)));
        }

        // Detections, and separately the known cameras we have driven past.
        //
        // Keeping these apart is deliberate and worth the extra cell. One is what this
        // phone's own radios matched; the other is what other people recorded on the
        // map. Collapsing them into a single "detections" number would make the app
        // claim credit for finding things it never heard — and since almost none of
        // these cameras broadcast, that number would be almost entirely other people's
        // work presented as a measurement.
        refreshHits();
        int radio = hits == null ? 0 : hits.size();
        vHits.setText(String.valueOf(radio));
        vHits.setTextColor(radio > 0 ? skin.accent(Skin.CH_ALERT) : skin.inkSecondary);

        countPassed();
        paintNearest(imperial);
        paintMiniMap();
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, TICK_MS);
    }

    /**
     * Keep the embedded map on top of us.
     *
     * The marks are rebuilt every few seconds rather than every tick: the camera table
     * is a database query and the detections list another, and doing both at 1 Hz while
     * the rest of the screen is also updating makes the whole view stutter. The position
     * itself follows every tick, which is the part the eye notices.
     */
    private void paintMiniMap() {
        if (miniMap == null || fix == null) return;
        miniMap.setMe(fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(),
                fix.hasBearing() ? fix.getBearing() : Double.NaN);
        if (!miniMap.isCentred() || miniMap.zoomLevel() < 13) {
            miniMap.centre(fix.getLatitude(), fix.getLongitude(), 16);
        } else {
            miniMap.centre(fix.getLatitude(), fix.getLongitude());
        }

        long now = System.currentTimeMillis();
        if (now - mapPainted < 5000) return;
        mapPainted = now;

        java.util.List<TileMapView.Mark> marks = new java.util.ArrayList<>();
        try {
            for (Alpr.Cam cm : Alpr.near(this, fix.getLatitude(), fix.getLongitude(),
                                         3000, 200)) {
                TileMapView.Mark m = new TileMapView.Mark();
                m.lat = cm.lat; m.lon = cm.lon;
                m.colour = skin.accent(Skin.CH_RSRQ);
                m.radiusDp = 4.5f;
                m.ring = true;                 // someone else's report, not our measurement
                marks.add(m);
            }
        } catch (Throwable ignored) { }
        if (hits != null) {
            for (Store.Hit h : hits) {
                if (Double.isNaN(h.lat)) continue;
                TileMapView.Mark m = new TileMapView.Mark();
                m.lat = h.lat; m.lon = h.lon;
                m.colour = skin.accent(Skin.CH_ALERT);
                m.radiusDp = 5f;
                marks.add(m);
            }
        }
        miniMap.setMarks(marks);
        if (mapHint != null) {
            int cams = 0;
            try { cams = Alpr.count(this); } catch (Throwable ignored) { }
            if (cams == 0) {
                mapHint.setText("No known cameras loaded. Detector screen → "
                        + "Load around me.");
                mapHint.setTextColor(skin.accent(Skin.CH_SAT));
            } else if (Alpr.outsideLoadedArea(this, fix.getLatitude(), fix.getLongitude())) {
                // Say so rather than showing an empty road. The layer going quiet
                // because you drove off the edge of it looks exactly like the layer
                // working and there being nothing there.
                mapHint.setText("Outside the area that was loaded — the road ahead is "
                        + "unknown, not empty. Loading more as you go.");
                mapHint.setTextColor(skin.accent(Skin.CH_ALERT));
            } else {
                // Both numbers, each next to what it means. The DETECTIONS cell counts
                // only what this phone's own radios matched, which is almost never the
                // same as how many cameras you drove past — most of them broadcast
                // nothing. Showing one number for both would quietly take credit for
                // other people's fieldwork.
                mapHint.setText(cams + " known cameras on file  ·  "
                        + passedCount + " passed this drive, within "
                        + Prefs.integer(this, Prefs.ALERT_RANGE_M, 400) + " m"
                        + "\n\nRings are positions other people recorded; filled dots "
                        + "are what this phone detected itself. DETECTIONS counts only "
                        + "the latter.");
                mapHint.setTextColor(skin.inkSecondary);
            }
        }
    }

    /**
     * The identity line: which site and sector, and on what band.
     *
     * LTE's split is fixed by 3GPP — the 28-bit cell identity is 20 bits of eNB and 8
     * of sector — so that one is exact. NR's is not: the gNB identifier may be 22 to 32
     * bits of the 36-bit NCI and the operator chooses, so any split is an assumption.
     * 24 bits is the common choice and the one used here, which means the gNB number
     * can be wrong on a network that configured something else. The raw identity is
     * still on the Signal screen, so nothing is lost by guessing here.
     */
    private String siteLabel(Sampler.Cell s) {
        String band = Sampler.has(s.band)
                ? ("NR".equals(s.tech) ? "  Band n" : "  Band ") + s.band
                : "";
        long id = rawId(s.ident);
        if (id < 0) {
            // No cell identity from the network. PCI is all we have, and it is not a
            // site id — say which it is rather than printing a number that looks like
            // one.
            return (Sampler.has(s.pci) ? "PCI " + s.pci : s.tech) + band;
        }
        if ("LTE".equals(s.tech)) {
            return "eNB " + (id >> 8) + ":" + (id & 0xFF) + band;
        }
        if ("NR".equals(s.tech)) {
            return "gNB " + (id >> 12) + ":" + (id & 0xFFF) + band;
        }
        return "ID " + id + band;
    }

    /** The numeric part of an identity like "lte:97309704", or -1 when there is none. */
    private static long rawId(String ident) {
        if (ident == null) return -1;
        int colon = ident.indexOf(':');
        if (colon < 0 || colon + 1 >= ident.length()) return -1;
        String tail = ident.substring(colon + 1);
        // The PCI fallback identity looks like "pci273@700" and is not a cell id.
        if (tail.startsWith("pci")) return -1;
        try { return Long.parseLong(tail); } catch (Throwable t) { return -1; }
    }

    /**
     * Known cameras this drive has passed within alerting range.
     *
     * Counted here rather than taken from the map layer, because "how many did I go
     * past" is a different question from "how many exist near me" — standing still
     * beside one should not keep incrementing it. Each camera counts once per visit to
     * this screen, keyed by its OSM id.
     */
    private final java.util.HashSet<Long> passed = new java.util.HashSet<>();
    private int passedCount;

    private void countPassed() {
        if (fix == null) return;
        try {
            int range = Prefs.integer(this, Prefs.ALERT_RANGE_M, 400);
            for (Alpr.Cam m : Alpr.near(this, fix.getLatitude(), fix.getLongitude(),
                                        range, 40)) {
                passed.add(m.osm);
            }
            passedCount = passed.size();
        } catch (Throwable ignored) { }
    }

    private int gradeDbm(int dbm) {
        if (!Sampler.has(dbm)) return skin.inkSecondary;
        if (dbm >= -95)  return skin.accent(Skin.CH_FREQ);
        if (dbm >= -110) return skin.accent(Skin.CH_SAT);
        return skin.accent(Skin.CH_ALERT);
    }

    /** Re-read at most every fifteen seconds; the list changes slowly. */
    private void refreshHits() {
        long now = System.currentTimeMillis();
        if (hits != null && now - hitsLoaded < 15000) return;
        try { hits = Store.of(this).hits(2000); } catch (Throwable t) { hits = null; }
        hitsLoaded = now;
    }

    /**
     * Closest logged detection to where we are now.
     *
     * Great-circle distance rather than a flat approximation. At these ranges the two
     * agree to well under a metre, but the flat version needs a cos(latitude) term that
     * is easy to drop, and a silently wrong range on this screen is worse than none.
     */
    private void paintNearest(boolean imperial) {
        // How long, and how far back, since anything was detected. Both halves: time
        // alone means nothing in traffic, distance alone says nothing about whether the
        // log is still live.
        if (sinceLine != null) {
            Store.Hit last = hits == null || hits.isEmpty() ? null : hits.get(0);
            if (last == null) {
                sinceLine.setText("");
            } else {
                double back = Double.NaN;
                if (fix != null && !Double.isNaN(last.lat)) {
                    back = haversine(fix.getLatitude(), fix.getLongitude(),
                                     last.lat, last.lon);
                }
                sinceLine.setText("last detection  " + Alert.since(last.ts, back, imperial));
            }
        }

        if (fix == null || hits == null || hits.isEmpty()) {
            nearName.setText(fix == null ? "waiting for a fix" : "none logged yet");
            // Neutral, not the alert colour: a red dash beside "none" reads as a
            // warning about nothing.
            nearRange.setText("");
            nearRange.setTextColor(skin.inkSecondary);
            nearBear.setText("");
            nearNote.setText("Detections appear here once the Detector has logged one "
                    + "with a position.");
            return;
        }
        Store.Hit best = null;
        double bestM = Double.MAX_VALUE;
        for (Store.Hit h : hits) {
            if (Double.isNaN(h.lat)) continue;
            double m = haversine(fix.getLatitude(), fix.getLongitude(), h.lat, h.lon);
            if (m < bestM) { bestM = m; best = h; }
        }
        if (best == null) {
            nearName.setText("none with a position");
            nearRange.setText("");
            nearRange.setTextColor(skin.inkSecondary);
            nearBear.setText("");
            return;
        }

        String label = best.name != null && !best.name.isEmpty() ? best.name
                     : best.vendor != null && !best.vendor.isEmpty() ? best.vendor
                     : best.rule != null ? best.rule : "detection";
        nearName.setText(label);

        if (imperial) {
            double ft = bestM * 3.280840;
            nearRange.setText(ft < 1000 ? Math.round(ft) + " ft"
                    : String.format(java.util.Locale.US, "%.2f mi", bestM / 1609.344));
        } else {
            nearRange.setText(bestM < 1000 ? Math.round(bestM) + " m"
                    : String.format(java.util.Locale.US, "%.2f km", bestM / 1000.0));
        }
        nearRange.setTextColor(bestM < 150 ? skin.accent(Skin.CH_ALERT)
                             : bestM < 800 ? skin.accent(Skin.CH_SAT)
                             : skin.inkSecondary);

        double brg = bearing(fix.getLatitude(), fix.getLongitude(), best.lat, best.lon);
        String compass = compass(brg);
        // Relative bearing only means something while actually moving; below walking
        // pace the GPS heading is noise and "ahead" would be a coin toss.
        if (fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() > 2.5f) {
            double rel = ((brg - fix.getBearing()) + 540) % 360 - 180;
            String side = Math.abs(rel) < 30 ? "ahead"
                        : Math.abs(rel) > 150 ? "behind"
                        : rel > 0 ? "right" : "left";
            nearBear.setText(compass + "  ·  " + side);
        } else {
            nearBear.setText(compass);
        }
        nearNote.setText(best.medium == null ? "" : best.medium
                + (best.rule == null ? "" : "  ·  " + best.rule)
                + "  ·  logged " + Sync.fmt(best.ts));
    }

    private static String compass(double deg) {
        String[] pts = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                        "S","SSW","SW","WSW","W","WNW","NW","NNW"};
        return pts[(int) Math.round(((deg % 360) / 22.5)) % 16];
    }

    private static double haversine(double la1, double lo1, double la2, double lo2) {
        double r = 6371008.8;
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = Math.toRadians(la2 - la1), dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double bearing(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * Hand off to Google Maps, on this screen, as a normal app.
     *
     * No projection, no second display. Running Maps inside this app was tried and
     * abandoned: the only mechanism that works needs a trusted virtual display, and
     * ADD_TRUSTED_DISPLAY cannot be granted to any app on this platform. What was
     * achievable instead — Maps on a system overlay display — looked rough and could
     * not take touches, so it is gone rather than half-shipped.
     */
    private void openMaps() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("geo:0,0"));
            i.setPackage("com.google.android.apps.maps");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("Google Maps is not available");
        }
    }

    private void toggleRecording() {
        boolean want = !Prefs.bool(this, Prefs.WARDRIVE_ON, false);
        Prefs.put(this, Prefs.WARDRIVE_ON, want);
        if (want) {
            MonitorService.start(this);
            toast("Recording");
        } else {
            toast("Recording stopped");
        }
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, 600);
    }
}

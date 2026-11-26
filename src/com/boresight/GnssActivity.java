package com.boresight;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GNSS: the sky, the signal levels, the fix, and a compass.
 *
 * On "calibration": there is no user-accessible way to calibrate a GNSS receiver, and any
 * app claiming otherwise is selling placebo. What genuinely exists is assistance data —
 * the almanac and ephemeris the receiver downloads to shorten time to first fix — and the
 * platform does expose commands to flush and refetch it. Those are the controls here.
 * The magnetometer is a different matter and is calibrated by motion; see the Sensors
 * screen, and the warning this screen shows when its accuracy drops.
 */
public class GnssActivity extends Screen implements SensorEventListener {

    private static final String ALL = "All";

    private LocationManager lm;
    private SensorManager sm;
    private Sensor rotation;

    private SkyView sky;
    private SkyView.Bars bars;
    private Graph cn0Graph, accGraph;
    private FlowLayout chipRow;

    private TextView fixValue, satValue, cn0Value, accValue, altValue, speedValue,
            bearingValue, ttffValue, provValue, constValue, headingValue;

    /** Everything the receiver reports, before the constellation filter. */
    private final List<SkyView.Sat> allSats = new ArrayList<>();
    private final Set<String> families = new LinkedHashSet<>();
    private String filter = ALL;

    private long startedAt, firstFixAt;
    private Object statusCallback;

    // Compass state. Heading is smoothed on the unit circle, because averaging degrees
    // across the 359/0 wrap gives nonsense.
    private final float[] rotMatrix = new float[9];
    private final float[] orientation = new float[3];
    private float headingSin, headingCos;
    private boolean headingSeeded;
    private float declination;          // magnetic to true, degrees east
    private int compassAccuracy = -1;

    @Override protected String title() { return "SATELLITES"; }

    @Override protected void content() {
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotation == null) rotation = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        startedAt = System.currentTimeMillis();

        LinearLayout skyBox = pane("Sky view");
        sky = new SkyView(this, th, skin);
        LinearLayout.LayoutParams slp = Theme.wrap();
        slp.height = th.dp(320);
        skyBox.addView(sky, slp);

        // Tapping the plot flips orientation, which is quicker than hunting for a button
        // while you are holding the phone up at the sky.
        sky.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleOrientation(); }
        });

        LinearLayout orient = actionRow(skyBox);
        smallAction(orient, "North up / Heading up", new Runnable() {
            @Override public void run() { toggleOrientation(); }
        });
        smallAction(orient, "Reset filter", new Runnable() {
            @Override public void run() { filter = ALL; rebuildChips(); apply(); }
        });

        chipRow = new FlowLayout(this).gap(th.dp(5));
        LinearLayout.LayoutParams clp = Theme.wrap();
        clp.setMargins(0, th.dp(6), 0, 0);
        skyBox.addView(chipRow, clp);

        hint(skyBox, "Filled markers carry the fix, outlines are tracked but unused, and "
                   + "size follows carrier-to-noise density. Elevation runs from the "
                   + "horizon at the rim to overhead at the centre. In heading-up the sky "
                   + "turns with the phone and the arrow at the top is where you are "
                   + "pointing; the red needle is always true north.");

        LinearLayout barBox = pane("Carrier to noise");
        bars = new SkyView.Bars(this, th, skin, sky);
        LinearLayout.LayoutParams blp = Theme.wrap();
        blp.height = th.dp(120);
        barBox.addView(bars, blp);
        hint(barBox, "dB-Hz per satellite. The line marks 30 dB-Hz, around where a signal "
                   + "becomes useful for ranging.");

        LinearLayout nums = pane("Fix");
        fixValue     = kv(nums, "Status", "waiting");
        satValue     = kv(nums, "Satellites", "-");
        constValue   = kv(nums, "Constellations", "-");
        headingValue = kv(nums, "Heading", "-");
        cn0Value     = kv(nums, "Best C/N0", "-");
        accValue     = kv(nums, "Accuracy", "-");
        altValue     = kv(nums, "Altitude", "-");
        speedValue   = kv(nums, "Speed", "-");
        bearingValue = kv(nums, "Course", "-");
        provValue    = kv(nums, "Provider", "-");
        ttffValue    = kv(nums, "Time to first fix", "-");

        hint(nums, "Heading comes from the rotation vector sensor, which is referenced to "
                 + "magnetic north. GNSS azimuths are referenced to true north, so the "
                 + "local declination is added before anything is plotted. Course is "
                 + "different again: it is the direction you are actually travelling, "
                 + "which only means anything while moving.");

        LinearLayout traces = pane("Trends");
        cn0Graph = graph(traces, Skin.CH_SAT, "mean C/N0 dB-Hz", 0, 50, 10, 86);
        accGraph = graph(traces, Skin.CH_RSRP, "accuracy m", 0, 50, 10, 86);

        LinearLayout tools = pane("Assistance data");
        hint(tools, "These flush and refetch the almanac and ephemeris the receiver uses "
                  + "to find satellites quickly. Useful after a long flight or a cold "
                  + "start that will not lock. Neither one calibrates anything, because "
                  + "nothing about a GNSS receiver is user-calibratable.");
        LinearLayout row = actionRow(tools);
        smallAction(row, "Refetch assist", new Runnable() {
            @Override public void run() { extra("force_xtra_injection", "Assistance refetch"); }
        });
        smallAction(row, "Refresh time", new Runnable() {
            @Override public void run() { extra("force_time_injection", "Time refetch"); }
        });
        danger(tools, "Delete assistance data (cold start)",
                "This clears the stored almanac and ephemeris. The next fix will take "
              + "much longer — up to a few minutes under open sky. Continue?",
                new Runnable() {
            @Override public void run() { extra("delete_aiding_data", "Assistance cleared"); }
        });

        LinearLayout service = pane("System screens");
        hint(service, "Samsung keeps its own GPS test page behind a dialer code. It shows "
                   + "the receiver's internal state, which the public API does not expose.");
        LinearLayout r2 = actionRow(service);
        smallAction(r2, "*#1575#", new Runnable() {
            @Override public void run() { dial("*#1575"); }
        });
        smallAction(r2, "*#0011#", new Runnable() {
            @Override public void run() { dial("*#0011"); }
        });
        smallAction(r2, "Location settings", new Runnable() {
            @Override public void run() {
                try {
                    startActivity(new Intent(android.provider.Settings
                            .ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception e) { toast("Could not open settings"); }
            }
        });

        rebuildChips();
    }

    private void toggleOrientation() {
        if (!sky.hasHeading() && !sky.isHeadingUp()) {
            toast("No compass reading yet");
            return;
        }
        sky.setHeadingUp(!sky.isHeadingUp());
        toast(sky.isHeadingUp() ? "Heading up" : "North up");
    }

    // ------------------------------------------------------------------ filter

    private void rebuildChips() {
        if (chipRow == null) return;
        chipRow.removeAllViews();
        List<String> names = new ArrayList<>();
        names.add(ALL);
        names.addAll(families);
        if (!names.contains(filter)) filter = ALL;

        for (final String name : names) {
            boolean on = name.equals(filter);
            int tint = ALL.equals(name) ? skin.accent(Skin.CH_RSRP) : sky.colourFor(name);
            TextView t = th.text(this, name + countSuffix(name), 11,
                    on ? skin.windowFlat() : skin.inkSecondary);
            t.setTypeface(skin.face(false, on ? Typeface.BOLD : Typeface.NORMAL));
            t.setPadding(th.dp(10), th.dp(5), th.dp(10), th.dp(5));
            t.setBackground(on ? th.solid(tint, skin.badgeCornerRadius)
                               : skin.badgeBg(th, 0x00000000));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, th.dp(5), 0);
            t.setLayoutParams(lp);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    filter = name;
                    rebuildChips();
                    apply();
                }
            });
            chipRow.addView(t);
        }
    }

    private String countSuffix(String name) {
        int n = 0;
        for (SkyView.Sat s : allSats) {
            if (ALL.equals(name) || name.equals(s.constellation)) n++;
        }
        return n == 0 ? "" : "  " + n;
    }

    private List<SkyView.Sat> filtered() {
        if (ALL.equals(filter)) return allSats;
        List<SkyView.Sat> out = new ArrayList<>();
        for (SkyView.Sat s : allSats) if (filter.equals(s.constellation)) out.add(s);
        return out;
    }

    private int usedNow;
    private boolean haveFix;
    private String lastProvider;

    /**
     * Say what the fix actually is.
     *
     * This read "locked" the moment any Location arrived, which indoors is a fused or
     * network position with no satellite behind it — so the screen said "locked" directly
     * above "0 used of 14 tracked". A GNSS screen that calls a Wi-Fi position a lock is
     * worse than one that says nothing.
     */
    private void paintFixLine() {
        if (fixValue == null) return;
        if (!haveFix) { fixValue.setText("no fix"); return; }
        if (usedNow > 0) {
            fixValue.setText("locked on " + usedNow
                    + (usedNow == 1 ? " satellite" : " satellites"));
        } else {
            fixValue.setText("position from "
                    + (lastProvider == null ? "the platform" : lastProvider)
                    + ", no satellites in it");
        }
    }

    /** Push the current filter through to both plots and the counters. */
    private void apply() {
        List<SkyView.Sat> shown = filtered();
        sky.set(shown);
        bars.set(shown);

        int used = 0, withCn0 = 0;
        float best = 0, sum = 0;
        for (SkyView.Sat s : shown) {
            if (s.usedInFix) used++;
            if (s.cn0 > 0) { best = Math.max(best, s.cn0); sum += s.cn0; withCn0++; }
        }

        satValue.setText(used + " used of " + shown.size() + " tracked"
                + (ALL.equals(filter) ? "" : "   (" + filter + " only)"));
        usedNow = used;
        paintFixLine();
        cn0Value.setText(best > 0
                ? String.format(java.util.Locale.US, "%.1f dB-Hz", best) : "-");
        constValue.setText(families.isEmpty() ? "-"
                : android.text.TextUtils.join(", ", families));
        if (withCn0 > 0) cn0Graph.push(sum / withCn0); else cn0Graph.pushGap();

        status(used + " in fix   " + shown.size() + " tracked",
                families.size() + " systems"
                        + (ALL.equals(filter) ? "" : "   filter " + filter));
    }

    // ------------------------------------------------------------------ platform

    private void extra(String command, String label) {
        try {
            boolean ok = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, command, null);
            toast(ok ? label + " requested" : label + ": the provider declined");
        } catch (SecurityException e) {
            toast("Needs location permission");
        } catch (Throwable t) {
            toast(label + ": not supported on this build");
        }
    }

    private void dial(String partial) {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(partial))));
            toast("Press # on the dialpad");
        } catch (Exception e) { toast("No dialer available"); }
    }

    // ------------------------------------------------------------------ listening

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if (firstFixAt == 0) firstFixAt = System.currentTimeMillis();
            paintFix(l);
        }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
    };

    @Override protected void onResume() {
        super.onResume();
        if (rotation != null) {
            sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            status("location permission needed", "");
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locListener);
        } catch (Throwable ignored) {}

        GnssStatus.Callback cb = new GnssStatus.Callback() {
            @Override public void onSatelliteStatusChanged(GnssStatus s) { absorb(s); }
        };
        statusCallback = cb;
        try {
            lm.registerGnssStatusCallback(getMainExecutor(), cb);
        } catch (Throwable ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        try { lm.removeUpdates(locListener); } catch (Throwable ignored) {}
        if (statusCallback instanceof GnssStatus.Callback) {
            try {
                lm.unregisterGnssStatusCallback((GnssStatus.Callback) statusCallback);
            } catch (Throwable ignored) {}
        }
    }

    private void absorb(GnssStatus s) {
        allSats.clear();
        families.clear();
        for (int i = 0; i < s.getSatelliteCount(); i++) {
            SkyView.Sat sat = new SkyView.Sat();
            sat.svid = s.getSvid(i);
            sat.constellation = SkyView.constellationName(s.getConstellationType(i));
            sat.azimuth = s.getAzimuthDegrees(i);
            sat.elevation = s.getElevationDegrees(i);
            sat.cn0 = s.getCn0DbHz(i);
            sat.usedInFix = s.usedInFix(i);
            sat.hasAlmanac = s.hasAlmanacData(i);
            sat.hasEphemeris = s.hasEphemerisData(i);
            allSats.add(sat);
            families.add(sat.constellation);
        }
        rebuildChips();
        apply();
    }

    private void paintFix(Location l) {
        lastProvider = l.getProvider();
        haveFix = true;
        paintFixLine();
        accValue.setText(String.format(java.util.Locale.US, "%.1f m", l.getAccuracy()));
        accGraph.push(l.getAccuracy());
        altValue.setText(l.hasAltitude()
                ? String.format(java.util.Locale.US, "%.0f m", l.getAltitude()) : "-");
        speedValue.setText(l.hasSpeed()
                ? String.format(java.util.Locale.US, "%.1f m/s  (%.0f km/h)",
                        l.getSpeed(), l.getSpeed() * 3.6) : "-");
        bearingValue.setText(l.hasBearing()
                ? Math.round(l.getBearing()) + "°  " + Locate.compass(l.getBearing()) : "-");
        provValue.setText(l.getProvider());
        if (firstFixAt > 0) {
            ttffValue.setText(((firstFixAt - startedAt) / 1000) + " s from opening this screen");
        }

        // A position is what makes the magnetic-to-true correction possible at all.
        try {
            GeomagneticField gf = new GeomagneticField((float) l.getLatitude(),
                    (float) l.getLongitude(), (float) l.getAltitude(), l.getTime());
            declination = gf.getDeclination();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ compass

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR
                && e.sensor.getType() != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return;

        SensorManager.getRotationMatrixFromVector(rotMatrix, e.values);
        SensorManager.getOrientation(rotMatrix, orientation);
        // Azimuth of the top of the phone, held flat, against magnetic north.
        float magnetic = (float) Math.toDegrees(orientation[0]);
        float trueHeading = magnetic + declination;

        // Smooth on the unit circle: averaging degrees across the 359/0 wrap is nonsense.
        double rad = Math.toRadians(trueHeading);
        float sin = (float) Math.sin(rad), cos = (float) Math.cos(rad);
        if (!headingSeeded) {
            headingSin = sin; headingCos = cos; headingSeeded = true;
        } else {
            float a = 0.22f;
            headingSin += (sin - headingSin) * a;
            headingCos += (cos - headingCos) * a;
        }
        float smoothed = (float) ((Math.toDegrees(Math.atan2(headingSin, headingCos)) + 360) % 360);

        sky.setHeading(smoothed, compassAccuracy);
        if (headingValue != null) {
            String acc = compassAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                    ? "   unreliable"
                    : compassAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                        ? "   low accuracy" : "";
            headingValue.setText(Math.round(smoothed) + "°  " + Locate.compass(smoothed)
                    + String.format(java.util.Locale.US, "   decl %+.1f°", declination) + acc);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_ROTATION_VECTOR
                || sensor.getType() == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
                || sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            compassAccuracy = accuracy;
        }
    }
}

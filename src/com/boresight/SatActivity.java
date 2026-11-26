package com.boresight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which Starlink direct-to-cell satellite is overhead, and whether it is the one
 * carrying your connection.
 *
 * The second part is the interesting one and it cannot be read directly — nothing in
 * the telephony API names the spacecraft. What it can do is record the serving cell
 * every time the phone registers on the satellite network, and intersect that with the
 * satellites that were above the horizon at the time. One pass proves little; several
 * narrow it to a single object, and the cell identity to satellite mapping falls out of
 * your own data.
 */
public class SatActivity extends Screen implements android.hardware.SensorEventListener {

    /** Direct-to-cell works at high elevation; below this the link is not realistic. */
    private static final double MIN_ELEVATION = 20.0;
    /** PCS G-block downlink, roughly where direct-to-cell operates. */
    private static final double CARRIER_HZ = 1990e6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private boolean running;

    private Tles tles;
    private Sampler sampler;
    private SkyView sky;
    private TextView elementsLine, statusLine, servingLine;
    private LinearLayout overheadBox, passBox, correlateBox;

    private double obsLat = Double.NaN, obsLon, obsAltKm;

    // Compass, so the sky can be turned to match where the phone is pointing.
    private android.hardware.SensorManager sensors;
    private android.hardware.Sensor rotation;
    private final float[] rotMatrix = new float[9];
    private final float[] orientation = new float[3];
    private float headingSin, headingCos;
    private boolean headingSeeded;
    private float declination;
    private int compassAccuracy = -1;

    @Override protected String title() { return "DIRECT TO CELL"; }

    @Override protected void content() {
        tles = new Tles(this);
        sampler = new Sampler(this);
        worker = Executors.newSingleThreadExecutor();
        sensors = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotation = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);

        LinearLayout head = pane("Element set");
        elementsLine = kv(head, "Elements", "checking");
        statusLine = kv(head, "Status", "-");
        LinearLayout row = actionRow(head);
        smallAction(row, "Update elements", new Runnable() {
            @Override public void run() { fetch(); }
        });
        smallAction(row, "Reload", new Runnable() {
            @Override public void run() { reload(); }
        });
        smallAction(row, "Import file", new Runnable() {
            @Override public void run() {
                java.io.File p = tles.importPath();
                info("Import an element set",
                        "Put a CelesTrak TLE file here and press Reload:\n\n"
                      + (p == null ? "(no external storage)" : p.getAbsolutePath())
                      + "\n\nUseful when CelesTrak is rate limiting, or to load a set "
                      + "before heading somewhere with no coverage.");
            }
        });
        hint(head, "A 1.7 MB download from CelesTrak, only ever when you press it. The "
                 + "set stays usable for days, so it is worth fetching before you head "
                 + "somewhere without coverage.");

        LinearLayout skyBox = pane("Overhead now");
        sky = new SkyView(this, th, skin);
        LinearLayout.LayoutParams slp = Theme.wrap();
        slp.height = th.dp(300);
        skyBox.addView(sky, slp);
        sky.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!sky.hasHeading()) { toast("No compass reading yet"); return; }
                sky.setHeadingUp(!sky.isHeadingUp());
                toast(sky.isHeadingUp() ? "Heading up" : "North up");
            }
        });
        overheadBox = th.column(this);
        skyBox.addView(overheadBox, Theme.wrap());

        LinearLayout serving = pane("Your connection");
        servingLine = kv(serving, "Network", "-");
        correlateBox = th.column(this);
        serving.addView(correlateBox, Theme.wrap());
        hint(serving, "Nothing in the telephony API names the spacecraft. When the phone "
                   + "registers on the satellite network this records the serving cell "
                   + "and which satellites were above the horizon; across several passes "
                   + "the overlap narrows to one.");

        LinearLayout passes = pane("Next passes");
        passBox = th.column(this);
        passes.addView(passBox, Theme.wrap());

        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        if (rotation != null) {
            sensors.registerListener(this, rotation,
                    android.hardware.SensorManager.SENSOR_DELAY_UI);
        }
        fixObserver();
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        sensors.unregisterListener(this);
        ui.removeCallbacksAndMessages(null);
    }

    @Override public void onSensorChanged(android.hardware.SensorEvent e) {
        if (e.sensor.getType() != android.hardware.Sensor.TYPE_ROTATION_VECTOR) return;
        android.hardware.SensorManager.getRotationMatrixFromVector(rotMatrix, e.values);
        android.hardware.SensorManager.getOrientation(rotMatrix, orientation);
        // Satellite azimuths are against true north; the sensor reports magnetic.
        float trueHeading = (float) Math.toDegrees(orientation[0]) + declination;
        double rad = Math.toRadians(trueHeading);
        float sn = (float) Math.sin(rad), cs = (float) Math.cos(rad);
        if (!headingSeeded) { headingSin = sn; headingCos = cs; headingSeeded = true; }
        else {
            headingSin += (sn - headingSin) * 0.22f;
            headingCos += (cs - headingCos) * 0.22f;
        }
        float smoothed = (float) ((Math.toDegrees(Math.atan2(headingSin, headingCos)) + 360) % 360);
        sky.setHeading(smoothed, compassAccuracy);
    }

    @Override public void onAccuracyChanged(android.hardware.Sensor s, int accuracy) {
        compassAccuracy = accuracy;
    }

    private void tick() {
        if (!running) return;
        refresh();
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, 2000);
    }

    // ------------------------------------------------------------------ elements

    private void fetch() {
        setStatus("starting");
        worker.execute(new Runnable() {
            @Override public void run() {
                boolean ok = tles.fetch(new Tles.Progress() {
                    @Override public void onProgress(String m) { setStatus(m); }
                });
                if (ok) {
                    tles.load();
                    post();
                    setStatus("ready");
                }
            }
        });
    }

    private void reload() {
        setStatus("loading");
        worker.execute(new Runnable() {
            @Override public void run() {
                boolean ok = tles.load();
                post();
                setStatus(ok ? "ready" : "no elements — press Update");
            }
        });
    }

    private void post() {
        ui.post(new Runnable() {
            @Override public void run() {
                elementsLine.setText(tles.directToCell().size() + " direct-to-cell of "
                        + tles.all().size() + "   ·   " + tles.freshness());
                refresh();
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() { @Override public void run() { statusLine.setText(s); } });
    }

    // ------------------------------------------------------------------ observer

    private void fixObserver() {
        Location l = MonitorService.lastFix();
        if (l == null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (Throwable ignored) {}
        }
        if (l != null) {
            obsLat = l.getLatitude();
            obsLon = l.getLongitude();
            obsAltKm = l.hasAltitude() ? l.getAltitude() / 1000.0 : 0;
            try {
                android.hardware.GeomagneticField gf = new android.hardware.GeomagneticField(
                        (float) obsLat, (float) obsLon, (float) l.getAltitude(), l.getTime());
                declination = gf.getDeclination();
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        if (tles.directToCell().isEmpty()) {
            sky.set(new ArrayList<SkyView.Sat>());
            return;
        }
        if (Double.isNaN(obsLat)) {
            fixObserver();
            if (Double.isNaN(obsLat)) {
                status("no position yet", "");
                return;
            }
        }

        double jd = Geo.julianFromMillis(System.currentTimeMillis());
        List<Tles.Sighting> up = tles.overhead(jd, obsLat, obsLon, obsAltKm, 0);

        // Feed the existing sky plot; the satellite number stands in for the PRN.
        List<SkyView.Sat> marks = new ArrayList<>();
        for (Tles.Sighting s : up) {
            SkyView.Sat m = new SkyView.Sat();
            m.svid = s.tle.satnum % 10000;
            m.constellation = "DTC";
            m.azimuth = (float) s.look.azimuth;
            m.elevation = (float) s.look.elevation;
            // Borrow the C/N0 channel to size markers by how usable the geometry is.
            m.cn0 = (float) Math.max(0, Math.min(45, s.look.elevation / 2.0));
            m.usedInFix = s.look.elevation >= MIN_ELEVATION;
            marks.add(m);
        }
        sky.set(marks);

        paintOverhead(up);
        paintServing(up);
        paintPasses(jd, up);

        int usable = 0;
        for (Tles.Sighting s : up) if (s.look.elevation >= MIN_ELEVATION) usable++;
        status(up.size() + " above horizon   " + usable + " above "
                + (int) MIN_ELEVATION + "°",
                String.format(Locale.US, "%.4f, %.4f", obsLat, obsLon));
    }

    private void paintOverhead(List<Tles.Sighting> up) {
        overheadBox.removeAllViews();
        if (up.isEmpty()) {
            overheadBox.addView(note("Nothing above the horizon."));
            return;
        }
        int shown = 0;
        for (Tles.Sighting s : up) {
            if (shown++ >= 8) break;
            boolean usable = s.look.elevation >= MIN_ELEVATION;
            // One line wrapped awkwardly; two short ones read better and let the
            // identity stand out from the geometry.
            LinearLayout rowBox = th.column(this);
            rowBox.setPadding(0, th.dp(3), 0, th.dp(3));

            TextView head = th.num(this, String.format(Locale.US, "%-16s  el %4.1f   az %5.1f",
                    trim(s.shortName(), 16), s.look.elevation, s.look.azimuth), 11,
                    usable ? skin.accent(Skin.CH_RSRP) : skin.inkSecondary);
            head.setTypeface(skin.face(true, usable ? Typeface.BOLD : Typeface.NORMAL));
            rowBox.addView(head);

            TextView sub = th.num(this, String.format(Locale.US,
                    "    %5.0f km   %+5.2f km/s   %+7.0f Hz   %s",
                    s.look.range, s.look.rangeRate, s.look.dopplerHz(CARRIER_HZ),
                    s.look.rangeRate < 0 ? "closing" : "receding"), 9,
                    Skin.alpha(skin.inkSecondary, 0.9f));
            rowBox.addView(sub);
            overheadBox.addView(rowBox);
        }
        if (up.size() > 8) {
            overheadBox.addView(note("and " + (up.size() - 8) + " lower"));
        }
    }

    /**
     * Record the pairing whenever the phone is actually on the satellite network, and
     * show the current overlap.
     */
    private void paintServing(List<Tles.Sighting> up) {
        correlateBox.removeAllViews();
        Sampler.Snap snap = sampler.sample();
        boolean onSat = Plmn.satellite(snap.plmn);
        servingLine.setText(snap.title()
                + (onSat ? "   ·   SATELLITE" : "   ·   terrestrial"));

        if (!onSat) {
            correlateBox.addView(note(
                    "Not on the satellite network, so there is nothing to correlate. "
                  + "This fills in when the phone falls back to T-Satellite."));
            return;
        }

        List<Tles.Sighting> candidates = new ArrayList<>();
        for (Tles.Sighting s : up) {
            if (s.look.elevation >= MIN_ELEVATION) candidates.add(s);
        }
        String cell = snap.serving != null && snap.serving.ident != null
                ? snap.serving.ident : "unknown cell";

        correlateBox.addView(note("Serving cell " + cell + " with "
                + candidates.size() + " satellite"
                + (candidates.size() == 1 ? "" : "s") + " above "
                + (int) MIN_ELEVATION + "°:"));
        for (Tles.Sighting s : candidates) {
            TextView t = th.num(this, "   " + s.shortName()
                    + String.format(Locale.US, "   el %.1f°   %.0f km",
                            s.look.elevation, s.look.range),
                    10, skin.accent(Skin.CH_ALERT));
            correlateBox.addView(t);
        }
        if (candidates.size() == 1) {
            correlateBox.addView(note("Only one candidate — that is almost certainly it."));
        } else if (candidates.size() > 1) {
            correlateBox.addView(note(
                    "Several candidates. Repeat observations on this cell identity and "
                  + "the common satellite will emerge."));
        }
    }

    private void paintPasses(double jd, List<Tles.Sighting> up) {
        passBox.removeAllViews();
        if (up.isEmpty()) return;

        // Predicting for the whole set is wasteful; the ones already in view, plus the
        // best few, are what matters in the next hour.
        Geo.Vec obs = Geo.geodeticToEcef(obsLat, obsLon, obsAltKm);
        List<Geo.Pass> found = new ArrayList<>();
        int checked = 0;
        for (Sgp4.Tle t : tles.directToCell()) {
            if (checked++ > 120) break;
            Sgp4 s = new Sgp4(t);
            if (!s.valid()) continue;
            List<Geo.Pass> ps = Geo.passes(s, obs, obsLat, obsLon, jd, 1.0,
                    MIN_ELEVATION, 30);
            found.addAll(ps);
            if (found.size() > 12) break;
        }
        if (found.isEmpty()) {
            passBox.addView(note("No passes above " + (int) MIN_ELEVATION
                    + "° found in the next hour from the first slice of the set."));
            return;
        }
        java.util.Collections.sort(found, new java.util.Comparator<Geo.Pass>() {
            @Override public int compare(Geo.Pass a, Geo.Pass b) {
                return Double.compare(a.riseJd, b.riseJd);
            }
        });
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
        int n = 0;
        for (Geo.Pass p : found) {
            if (n++ >= 8) break;
            String name = p.tle.name.replace("[DTC]", "").trim();
            TextView t = th.num(this, String.format(Locale.US,
                    "%-16s %s  %4.1f min  peak %4.1f° az %3.0f",
                    trim(name, 16), f.format(new Date(p.riseMillis())),
                    p.durationMin(), p.peakElevation, p.peakAz), 10, skin.inkSecondary);
            t.setPadding(0, th.dp(2), 0, th.dp(2));
            passBox.addView(t);
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private TextView note(String s) {
        TextView t = th.text(this, s, 11, skin.inkSecondary);
        t.setPadding(0, th.dp(4), 0, th.dp(4));
        return t;
    }
}

package com.boresight;

import android.content.Context;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every sensor the phone will admit to, with a live trace for the ones worth watching.
 *
 * Sensors are only listened to while their card is expanded, because registering the lot
 * at the fastest rate heats the phone and tells you nothing you can read. Tap a card to
 * start its trace; tap again to stop.
 */
public class SensorsActivity extends Screen implements SensorEventListener {

    private SensorManager sm;

    private static class Card {
        Sensor sensor;
        Graph graph;
        TextView value, meta;
        boolean live;
        float[] last;
        long events;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
    }

    private final List<Card> cards = new ArrayList<>();
    private final Map<Sensor, Card> bySensor = new HashMap<>();

    @Override protected String title() { return "SENSORS"; }

    @Override protected void content() {
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        probeAll(all);

        LinearLayout intro = pane("Compass calibration");
        hint(intro, "There is no API that calibrates a magnetometer. Android recalibrates "
                  + "it from motion, which is what the figure-of-eight gesture is for: "
                  + "rotate the phone through all three axes for a few seconds while "
                  + "watching the accuracy field on the magnetic field card below. It "
                  + "will climb from unreliable to high. Keep away from steel and "
                  + "magnets while you do it.");
        action(intro, "Open magnetic field card", new Runnable() {
            @Override public void run() {
                for (Card c : cards) {
                    if (c.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD && !c.live) {
                        toggle(c);
                        break;
                    }
                }
                toast("Rotate the phone through all axes");
            }
        });

        // Group by the broad families, so the list is navigable.
        addGroup("Motion", all, new int[]{
                Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY,
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_STEP_COUNTER,
                Sensor.TYPE_SIGNIFICANT_MOTION, Sensor.TYPE_STEP_DETECTOR,
                Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED});
        addGroup("Position", all, new int[]{
                Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
                Sensor.TYPE_ORIENTATION, Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, Sensor.TYPE_PROXIMITY});
        addGroup("Environment", all, new int[]{
                Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT,
                Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY,
                Sensor.TYPE_TEMPERATURE});

        // Everything else, including whatever Samsung ships that AOSP has no name for.
        List<Sensor> rest = new ArrayList<>();
        for (Sensor s : all) if (!bySensor.containsKey(s) && usable(s)) rest.add(s);
        if (!rest.isEmpty()) {
            LinearLayout box = pane("Vendor and uncategorised  ·  " + rest.size());
            hint(box, "Sensors with no AOSP type constant. Samsung ships a good number; "
                    + "the names are the vendor's own.");
            for (Sensor s : rest) box.addView(card(s, false));
        }

        unavailable(all);
        status(cards.size() + " of " + all.size() + " usable", "tap a card to trace it");
    }

    // ------------------------------------------------- what will actually start

    /** Probe results, worked out once in {@link #probeAll}. */
    private final Map<Sensor, String> refused = new HashMap<>();
    private final List<Sensor> triggerOnly = new ArrayList<>();

    /**
     * Find out which sensors will actually produce data, by asking them.
     *
     * The list from getSensorList is a hardware inventory, not a list of what this app
     * may use. On this phone 44 sensors are advertised and a good third of them are
     * behind com.samsung.permission.SSENSOR, which is signature-level and therefore
     * unobtainable — listing them as if they were available means every tap on one is
     * a dead end.
     *
     * One-shot sensors are a separate case and not a failure: significant motion and
     * friends are driven by requestTriggerSensor, not registerListener, so a false
     * return there says nothing about availability.
     *
     * The probe is a register immediately followed by an unregister. That is the only
     * honest test — the permission check happens inside the framework, and there is no
     * API that asks "may I" without trying.
     */
    private void probeAll(List<Sensor> all) {
        for (Sensor s : all) {
            if (s.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
                triggerOnly.add(s);
                continue;
            }
            boolean ok;
            try {
                ok = sm.registerListener(probe, s, SensorManager.SENSOR_DELAY_NORMAL);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) {
                try { sm.unregisterListener(probe, s); } catch (Throwable ignored) { }
            } else {
                refused.put(s, whyRefused(s));
            }
        }
    }

    /** A listener that does nothing; the probe only cares whether registration took. */
    private final android.hardware.SensorEventListener probe =
            new android.hardware.SensorEventListener() {
        @Override public void onSensorChanged(android.hardware.SensorEvent e) { }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    private boolean usable(Sensor s) {
        return !refused.containsKey(s) && !triggerOnly.contains(s);
    }

    /** name → required permission, read from the platform rather than guessed. */
    private Map<String, String> perms;

    /**
     * Which permission a sensor needs, taken from the platform.
     *
     * An earlier version pattern-matched on the sensor's name and got it wrong twice
     * on this phone alone: it called the rear ambient-light sensor a body sensor
     * because its type string contains "bio", and claimed Samsung's Seq Step wanted
     * ACTIVITY_RECOGNITION when it actually wants SSENSOR. A plausible wrong reason is
     * worse than no reason, so the guessing is gone.
     *
     * Sensor.getRequiredPermission() exists but is not public SDK, hence the
     * reflection; dumpsys is the fallback and needs the privileged backend.
     */
    private String whyRefused(Sensor s) {
        String perm = null;
        try {
            java.lang.reflect.Method m = Sensor.class.getMethod("getRequiredPermission");
            m.setAccessible(true);
            Object v = m.invoke(s);
            if (v instanceof String && !((String) v).isEmpty()) perm = (String) v;
        } catch (Throwable ignored) { }

        if (perm == null) {
            if (perms == null) perms = readPerms();
            perm = perms.get(s.getName());
        }
        if (perm == null || perm.isEmpty()) {
            return "the framework refused it and does not say which permission it "
                 + "wants; nothing here is guessing on its behalf";
        }
        String note;
        if (perm.startsWith("com.samsung")) {
            note = " — a Samsung signature permission, which no app outside Samsung's "
                 + "own can hold";
        } else if (perm.endsWith("ACTIVITY_RECOGNITION") || perm.endsWith("BODY_SENSORS")) {
            note = " — a runtime permission this app does not ask for";
        } else {
            note = "";
        }
        return "needs " + perm + note;
    }

    /** Permissions from dumpsys sensorservice, where the backend can run it. */
    private Map<String, String> readPerms() {
        Map<String, String> out = new HashMap<>();
        try {
            Shell.Result r = Shell.get(this).exec("dumpsys sensorservice 2>/dev/null", 12);
            if (!r.ran || r.out == null) return out;
            for (String line : r.out.split("\n")) {
                int p = line.indexOf("perm:");
                int bar = line.indexOf('|');
                if (p < 0 || bar < 0) continue;
                // "0x...) <name>  | vendor | ... | perm: <perm> | flags: ..."
                int close = line.indexOf(')');
                if (close < 0 || close > bar) continue;
                String name = line.substring(close + 1, bar).trim();
                String perm = line.substring(p + 5).trim();
                int end = perm.indexOf('|');
                if (end > 0) perm = perm.substring(0, end).trim();
                if (!name.isEmpty() && !perm.isEmpty()) out.put(name, perm);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void unavailable(List<Sensor> all) {
        if (refused.isEmpty() && triggerOnly.isEmpty()) return;

        LinearLayout box = pane("Will not start  ·  "
                + (refused.size() + triggerOnly.size()));
        hint(box, "Advertised by the hardware but not usable from here, so they are "
                + "kept out of the lists above rather than sitting there as dead taps. "
                + "Checked by trying to register each one, not by guessing.");

        for (Map.Entry<Sensor, String> e : refused.entrySet()) {
            LinearLayout row = th.column(this);
            row.setPadding(0, th.dp(6), 0, th.dp(6));
            row.addView(th.num(this, e.getKey().getName(), 11,
                    skin.accent(Skin.CH_ALERT)));
            row.addView(th.text(this, e.getValue(), 9,
                    Skin.alpha(skin.inkSecondary, 0.9f)));
            box.addView(row, Theme.wrap());
        }
        for (Sensor s : triggerOnly) {
            LinearLayout row = th.column(this);
            row.setPadding(0, th.dp(6), 0, th.dp(6));
            row.addView(th.num(this, s.getName(), 11, skin.accent(Skin.CH_SAT)));
            row.addView(th.text(this, "one-shot sensor — fires once via "
                    + "requestTriggerSensor rather than streaming, so it has no trace "
                    + "to draw", 9, Skin.alpha(skin.inkSecondary, 0.9f)));
            box.addView(row, Theme.wrap());
        }
    }

    private void addGroup(String caption, List<Sensor> all, int[] types) {
        List<Sensor> picked = new ArrayList<>();
        for (int t : types) {
            for (Sensor s : all) {
                if (s.getType() == t && !picked.contains(s) && usable(s)) picked.add(s);
            }
        }
        if (picked.isEmpty()) return;
        LinearLayout box = pane(caption + "  ·  " + picked.size());
        for (Sensor s : picked) box.addView(card(s, true));
    }

    private View card(final Sensor s, boolean graphable) {
        final Card c = new Card();
        c.sensor = s;
        cards.add(c);
        bySensor.put(s, c);

        LinearLayout box = th.column(this);
        box.setPadding(0, th.dp(7), 0, th.dp(8));
        box.setBackground(th.ripple(th.solid(0x00000000, 0)));
        box.setClickable(true);

        LinearLayout top = th.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names = th.column(this);
        TextView name = th.text(this, prettyName(s), 12, skin.inkPrimary);
        name.setTypeface(skin.face(false, Typeface.BOLD));
        names.addView(name);

        StringBuilder sub = new StringBuilder();
        sub.append(s.getVendor() == null ? "" : s.getVendor());
        if (s.getMaximumRange() > 0) {
            sub.append("   range ").append(trimF(s.getMaximumRange()))
               .append(' ').append(unitFor(s));
        }
        if (s.getResolution() > 0) sub.append("   res ").append(trimF(s.getResolution()));
        sub.append("   ").append(s.getPower()).append(" mA");
        c.meta = th.num(this, sub.toString().trim(), 9, skin.inkSecondary);
        names.addView(c.meta);
        top.addView(names, Theme.weight(1f));

        c.value = th.num(this, "—", 12, skin.accent(Skin.CH_SINR));
        c.value.setTypeface(skin.face(true, Typeface.BOLD));
        c.value.setGravity(Gravity.END);
        top.addView(c.value);
        box.addView(top, Theme.wrap());

        if (graphable) {
            c.graph = new Graph(this, th, skin, 180)
                    .channel(Skin.CH_SINR)
                    .autoScale(true)
                    .grid(stepFor(s))
                    .unit(unitFor(s))
                    .value(false)
                    .note("tap to start");
            LinearLayout.LayoutParams lp = Theme.wrap();
            lp.height = th.dp(68);
            lp.setMargins(0, th.dp(5), 0, 0);
            box.addView(c.graph, lp);
            c.graph.setVisibility(View.GONE);
        }

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(c); }
        });
        return box;
    }

    private void toggle(Card c) {
        if (c.live) {
            sm.unregisterListener(this, c.sensor);
            c.live = false;
            if (c.graph != null) c.graph.setVisibility(View.GONE);
            c.value.setTextColor(skin.accent(Skin.CH_SINR));
        } else {
            boolean ok = sm.registerListener(this, c.sensor,
                    SensorManager.SENSOR_DELAY_UI);
            if (!ok) { toast("This sensor refused to start"); return; }
            c.live = true;
            c.events = 0;
            c.min = Float.MAX_VALUE; c.max = -Float.MAX_VALUE;
            if (c.graph != null) { c.graph.clear(); c.graph.setVisibility(View.VISIBLE); }
            c.value.setTextColor(skin.accent(Skin.CH_RSRP));
        }
        int liveCount = 0;
        for (Card x : cards) if (x.live) liveCount++;
        status(cards.size() + " sensors", liveCount + " streaming");
    }

    @Override protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        for (Card c : cards) {
            c.live = false;
            if (c.graph != null) c.graph.setVisibility(View.GONE);
        }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        Card c = bySensor.get(e.sensor);
        if (c == null || !c.live) return;
        c.last = e.values.clone();
        c.events++;

        StringBuilder s = new StringBuilder();
        int n = Math.min(e.values.length, 4);
        for (int i = 0; i < n; i++) {
            if (i > 0) s.append("  ");
            s.append(trimF(e.values[i]));
        }
        String unit = unitFor(e.sensor);
        if (!unit.isEmpty()) s.append(' ').append(unit);
        c.value.setText(s.toString());

        // Trace the magnitude for vectors, the raw value for scalars.
        float plot;
        if (e.values.length >= 3) {
            plot = (float) Math.sqrt(e.values[0] * e.values[0]
                    + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        } else {
            plot = e.values[0];
        }
        c.min = Math.min(c.min, plot);
        c.max = Math.max(c.max, plot);
        if (c.graph != null) c.graph.push(plot);

        StringBuilder meta = new StringBuilder();
        meta.append(c.events).append(" samples");
        if (c.min <= c.max) {
            meta.append("   span ").append(trimF(c.min)).append(" to ").append(trimF(c.max));
        }
        if (e.values.length >= 3) meta.append("   magnitude ").append(trimF(plot));
        c.meta.setText(meta.toString());
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) {
        Card c = bySensor.get(s);
        if (c == null) return;
        String word;
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:   word = "accuracy high"; break;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM: word = "accuracy medium"; break;
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:    word = "accuracy low"; break;
            case SensorManager.SENSOR_STATUS_UNRELIABLE:      word = "unreliable - needs motion"; break;
            default: word = "accuracy unknown";
        }
        c.meta.setText(word);
    }

    // ------------------------------------------------------------------ labels

    private static String prettyName(Sensor s) {
        String n = s.getName();
        return n == null || n.isEmpty() ? ("type " + s.getType()) : n;
    }

    private static String unitFor(Sensor s) {
        switch (s.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_LINEAR_ACCELERATION:
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED:
                return "m/s²";
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                return "rad/s";
            case Sensor.TYPE_MAGNETIC_FIELD:
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                return "µT";
            case Sensor.TYPE_PRESSURE:            return "hPa";
            case Sensor.TYPE_LIGHT:               return "lx";
            case Sensor.TYPE_PROXIMITY:           return "cm";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "°C";
            case Sensor.TYPE_RELATIVE_HUMIDITY:   return "%";
            case Sensor.TYPE_ORIENTATION:         return "°";
            default: return "";
        }
    }

    private static float stepFor(Sensor s) {
        switch (s.getType()) {
            case Sensor.TYPE_PRESSURE: return 2f;
            case Sensor.TYPE_LIGHT:    return 200f;
            case Sensor.TYPE_GYROSCOPE: return 1f;
            default: return 5f;
        }
    }

    private static String trimF(float v) {
        if (Math.abs(v) >= 1000) return String.valueOf(Math.round(v));
        if (Math.abs(v - Math.round(v)) < 0.005f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}

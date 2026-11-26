package com.boresight;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The audible side of a detection, for a driver who is not looking at the phone.
 *
 * Design constraints come from the use, not from taste. The phone is on a mount, the
 * road is loud, and the person is driving: a sound has to be distinguishable from the
 * navigation voice and the radio without being startling, it has to say something by
 * itself so the screen need not be read, and it must never fire so often that it gets
 * muted — a muted alert is worse than no alert, because it is trusted and silent.
 *
 * So: two short rising notes for a radio detection, one lower double-beep for a known
 * camera from the map, and a hard per-target cooldown so passing the same installation
 * twice in a minute speaks once.
 *
 * Deliberately a tone rather than a recorded sound or speech. ToneGenerator needs no
 * asset, no file, no permission and no Play Services, and it plays on the alarm stream,
 * which stays audible when media is ducked for navigation.
 */
public final class Alert {

    /** Don't speak about the same target again inside this window. */
    private static final long COOLDOWN_MS = 90_000;

    private static final Map<String, Long> lastSpoken = new HashMap<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Alert() { }

    public static boolean enabled(Context c) {
        return Prefs.bool(c, Prefs.ALERT_SOUND, true);
    }

    /**
     * A radio signature matched. Two rising notes.
     *
     * @param key something stable per target, normally the MAC, so repeat sightings of
     *            one camera do not chirp all the way down the street.
     */
    public static void detection(Context c, String key) {
        if (!enabled(c)) return;
        if (!fresh(key)) return;
        buzz(c, 80);
        tone(c, new int[]{ToneGenerator.TONE_PROP_BEEP, ToneGenerator.TONE_PROP_BEEP2},
             new int[]{160, 260});
    }

    /**
     * Approaching a camera that is already on the map. Lower and softer than a live
     * detection, because it is someone else's report rather than this phone's
     * measurement, and the two should never sound alike.
     */
    public static void approaching(Context c, String key) {
        if (!enabled(c)) return;
        if (!Prefs.bool(c, Prefs.ALERT_KNOWN, true)) return;
        if (!fresh(key)) return;
        buzz(c, 40);
        tone(c, new int[]{ToneGenerator.TONE_PROP_ACK}, new int[]{200});
    }

    /** True the first time, and once the cooldown has passed. */
    private static synchronized boolean fresh(String key) {
        if (key == null || key.isEmpty()) key = "unkeyed";
        long now = System.currentTimeMillis();
        Long prev = lastSpoken.get(key);
        if (prev != null && now - prev < COOLDOWN_MS) return false;
        lastSpoken.put(key, now);
        // The map is bounded by the drive, but a long survey still accumulates; drop
        // anything long expired rather than growing without limit.
        if (lastSpoken.size() > 500) {
            java.util.Iterator<Map.Entry<String, Long>> it = lastSpoken.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > COOLDOWN_MS * 4) it.remove();
            }
        }
        return true;
    }

    /** Forget the cooldowns, so a fresh drive speaks about everything again. */
    public static synchronized void reset() { lastSpoken.clear(); }

    private static void tone(final Context c, final int[] tones, final int[] msEach) {
        ui.post(new Runnable() {
            @Override public void run() {
                ToneGenerator g = null;
                try {
                    // Alarm stream: media is ducked while navigation speaks, and a
                    // detection that is inaudible under the nav voice is no use.
                    g = new ToneGenerator(AudioManager.STREAM_ALARM,
                            Prefs.integer(c, Prefs.ALERT_VOLUME, 80));
                    for (int i = 0; i < tones.length; i++) {
                        g.startTone(tones[i], msEach[i]);
                        Thread.sleep(msEach[i] + 40L);
                    }
                } catch (Throwable ignored) {
                } finally {
                    // ToneGenerator holds an AudioTrack. Leaking one per detection runs
                    // the device out of tracks on a long drive and then everything is
                    // silent, which is the worst possible way for this to fail.
                    if (g != null) try { g.release(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static void buzz(Context c, int ms) {
        if (!Prefs.bool(c, Prefs.ALERT_VIBRATE, true)) return;
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ since

    /**
     * "4 min ago · 2.3 mi back" — how long and how far since the last detection.
     *
     * Both halves matter and neither substitutes for the other. Time alone is
     * meaningless in traffic, and distance alone says nothing about whether the log is
     * still live. Returns an empty string when there is nothing to report, rather than
     * a zero that would read as a detection right here.
     */
    public static String since(long lastTs, double metresBack, boolean imperial) {
        if (lastTs <= 0) return "";
        long secs = Math.max(0, (System.currentTimeMillis() - lastTs) / 1000);
        StringBuilder b = new StringBuilder(elapsed(secs));
        if (!Double.isNaN(metresBack) && metresBack >= 0) {
            b.append("  ·  ").append(Alpr.distance(metresBack, imperial)).append(" back");
        }
        return b.toString();
    }

    public static String elapsed(long secs) {
        if (secs < 60) return secs + " s ago";
        if (secs < 3600) return (secs / 60) + " min ago";
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        return m == 0 ? h + " h ago"
                      : String.format(Locale.US, "%d h %d min ago", h, m);
    }
}

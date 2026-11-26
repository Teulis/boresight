package com.boresight;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Where the phone is, for a screen that is open, whether or not the survey is recording.
 *
 * Several screens were reading {@link MonitorService#lastFix()} and nothing else. That is
 * the recorder's fix, so with recording off it is null and the screen has no idea where
 * you are — the map drew no position marker at all and its centre-on-me button answered
 * "start monitoring to get one", which is a strange thing for a map to say.
 *
 * Looking at where you are is not recording where you have been, and should not require
 * turning on a logger. This asks for updates for exactly as long as a screen is in front
 * of the user, and stops the moment it is not: nothing is written to the survey, and
 * nothing keeps running in the background.
 *
 * The recorder's own fix is still preferred when there is one, because it is already
 * being produced at the survey's interval and a second subscription would only duplicate
 * it at a cost in battery.
 */
public final class Here {

    public interface OnFix { void fix(Location l); }

    private final Activity a;
    private final LocationManager lm;
    private final OnFix cb;
    private LocationListener listener;

    private Here(Activity activity, OnFix onFix) {
        a = activity;
        cb = onFix;
        lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
    }

    public static Here on(Activity a, OnFix onFix) { return new Here(a, onFix); }

    public static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The newest position available right now without waiting.
     *
     * Chosen by timestamp, not by source. The first cut of this preferred
     * MonitorService.lastFix() whenever it was non-null — but that is a static field that
     * outlives the service, so with recording stopped it keeps returning wherever the
     * recorder was when it last ran. Pressing centre-on-me put the map an hour's drive
     * from the phone, which is worse than showing nothing: a stale fix draws exactly
     * like a live one.
     *
     * Network and passive are included deliberately. Indoors a GPS fix may be hours old
     * or absent, and a coarse position that is right is more use on a map than a precise
     * one that is missing.
     */
    public static Location best(Context c) {
        Location newest = MonitorService.lastFix();
        if (!allowed(c)) return newest;
        LocationManager lm =
                (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return newest;

        String[] providers = Build.VERSION.SDK_INT >= 31
                ? new String[]{LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER,
                               LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}
                : new String[]{LocationManager.GPS_PROVIDER,
                               LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String p : providers) {
            try {
                Location cand = lm.getLastKnownLocation(p);
                if (cand == null) continue;
                if (newest == null || cand.getTime() > newest.getTime()) newest = cand;
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
        return newest;
    }

    /**
     * Start listening, and hand over the best cached position immediately so the screen
     * has something to draw before the first update arrives.
     */
    public void start(long intervalMs) {
        Location seed = best(a);
        if (seed != null) cb.fix(seed);

        if (!allowed(a) || lm == null || listener != null) return;

        listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) { cb.fix(l); }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
            // Deprecated from API 29 and still abstract on older platforms.
            @Override public void onStatusChanged(String p, int s, Bundle e) { }
        };

        String provider = LocationManager.GPS_PROVIDER;
        if (Build.VERSION.SDK_INT >= 31
                && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            provider = LocationManager.FUSED_PROVIDER;
        }
        try {
            lm.requestLocationUpdates(provider, intervalMs, 0f, listener);
            // Indoors GPS may never produce one. Network costs little and is often the
            // only thing that answers.
            if (!LocationManager.NETWORK_PROVIDER.equals(provider)
                    && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        Math.max(intervalMs, 5000), 0f, listener);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            listener = null;
        }
    }

    /** How old a fix is, in milliseconds, or Long.MAX_VALUE when there is none. */
    public static long ageMs(Location l) {
        if (l == null || l.getTime() <= 0) return Long.MAX_VALUE;
        return Math.max(0, System.currentTimeMillis() - l.getTime());
    }

    /** Must be called when the screen goes away. */
    public void stop() {
        if (listener == null || lm == null) return;
        try { lm.removeUpdates(listener); } catch (Throwable ignored) { }
        listener = null;
    }
}

package com.boresight;

import android.content.Context;
import android.provider.Settings;

/**
 * The Wi-Fi scan throttle, and turning it off.
 *
 * Android limits a foreground app to four {@code startScan()} calls per two minutes.
 * Standing in a building that is invisible; driving, it is the single biggest limit on
 * what a survey can see. At 45 mph a scan every 30 seconds samples the road once every
 * 2,000 feet, while anything worth spotting is in range for a couple of hundred. Roughly
 * nine tenths of the drive is never looked at, which is how a pass by thirty roadside
 * cameras can return nothing at all and still be working exactly as designed.
 *
 * The platform exposes the limit as a global setting. Writing it needs WRITE_SECURE_SETTINGS,
 * which an ordinary app cannot hold — but shell can, so the privileged backend can do it
 * when the user has one. Reading it needs nothing.
 *
 * This is a device-wide setting that outlives the app, so it is treated as borrowed:
 * nothing here turns it off on its own, the user asks, and {@link #restore} puts it back.
 */
public final class Throttle {

    private static final String KEY = "wifi_scan_throttle_enabled";

    private Throttle() { }

    /**
     * True when the platform is throttling scans. Absent means on: the setting is unset
     * on a stock device and the default is enabled, so treating null as "not throttled"
     * would have the service scanning hard on exactly the devices that will refuse it.
     */
    public static boolean isThrottled(Context c) {
        try {
            String v = Settings.Global.getString(c.getContentResolver(), KEY);
            if (v == null || v.isEmpty()) return true;
            return !"0".equals(v.trim());
        } catch (Throwable t) {
            return true;
        }
    }

    /** Whether we have a backend that could write the setting at all. */
    public static boolean canChange(Context c) {
        return Shell.get(c).privileged();
    }

    public static final class Result {
        public boolean ok;
        public String message = "";
    }

    private static Result set(Context c, boolean throttled) {
        Result r = new Result();
        if (isThrottled(c) == throttled) {
            r.ok = true;
            r.message = "Already " + (throttled ? "throttled." : "unthrottled.");
            return r;
        }
        if (!canChange(c)) {
            r.message = "This needs the privileged backend. Wi-Fi scan throttling is a "
                      + "secure setting, so an ordinary app is not allowed to write it.";
            return r;
        }
        Shell.Result s = Shell.get(c).exec(
                "settings put global " + KEY + " " + (throttled ? "1" : "0"), 8);
        if (!s.ran) {
            r.message = "The backend did not run the command.";
            return r;
        }
        // Read it back rather than trusting the exit code: settings put returns 0 on
        // some builds even when the write was refused.
        boolean now = isThrottled(c);
        r.ok = now == throttled;
        r.message = r.ok
                ? (throttled ? "Scan throttling restored."
                             : "Scan throttling off. Wi-Fi now scans every few seconds.")
                : "The command ran but the setting did not change.";
        return r;
    }

    /** Let the app scan as fast as it likes. Device-wide, and it outlives the app. */
    public static Result disable(Context c) { return set(c, false); }

    /** Put the platform default back. */
    public static Result restore(Context c) { return set(c, true); }

    /** One line for a settings screen. */
    public static String describe(Context c) {
        if (!isThrottled(c)) {
            return "Off — Wi-Fi scans every " + (MonitorService.fastScanSeconds()) + " s. "
                 + "This is a device-wide setting; turn it back on when you are done.";
        }
        return "On (platform default) — about one Wi-Fi scan every 30 s, which at "
             + "driving speed leaves most of the road unsampled.";
    }
}

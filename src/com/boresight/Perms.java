package com.boresight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * The runtime permissions this app actually asks for, in one place, each with the
 * reason it is asked for.
 *
 * The reasons are here rather than in a string table because they are the whole point.
 * A permission prompt with no context is a coin flip: the person either grants
 * everything without reading or denies something load-bearing and then finds the app
 * broken in a way they cannot diagnose. Location is the sharp case — it reads as
 * tracking, and the honest answer is more interesting than that: Android gates Wi-Fi
 * and Bluetooth scan results behind it, because a list of nearby radios locates you
 * whether or not the GPS is on. So denying location does not merely lose the map, it
 * empties the survey.
 *
 * Nothing here is optional-but-nagged. What the app can do without a permission, it
 * does; what it cannot, it says so on the screen where it would have happened.
 */
public final class Perms {

    private Perms() { }

    /** Everything we ask for at run time, filtered to what this Android version has. */
    public static String[] wanted(Context c) {
        List<String> all = new ArrayList<>();
        all.add(Manifest.permission.READ_PHONE_STATE);
        all.add(Manifest.permission.ACCESS_FINE_LOCATION);
        all.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            all.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            all.add(Manifest.permission.POST_NOTIFICATIONS);
            all.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return all.toArray(new String[0]);
    }

    /** Those of {@link #wanted} not yet granted. */
    public static List<String> missing(Context c) {
        List<String> need = new ArrayList<>();
        for (String p : wanted(c)) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        return need;
    }

    public static boolean has(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The two without which the live page has nothing to show. Everything else
     * degrades to a missing section rather than an empty app.
     */
    public static boolean core(Context c) {
        return has(c, Manifest.permission.ACCESS_FINE_LOCATION)
            && has(c, Manifest.permission.READ_PHONE_STATE);
    }

    /** Ask for whatever is still missing. Returns false if there was nothing to ask. */
    public static boolean request(android.app.Activity a, int requestCode) {
        List<String> need = missing(a);
        if (need.isEmpty()) return false;
        a.requestPermissions(need.toArray(new String[0]), requestCode);
        return true;
    }

    /** Short name for a permission, for a list a person reads. */
    public static String name(String p) {
        if (Manifest.permission.READ_PHONE_STATE.equals(p))     return "Phone state";
        if (Manifest.permission.ACCESS_FINE_LOCATION.equals(p))  return "Precise location";
        if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(p)) return "Approximate location";
        if (Build.VERSION.SDK_INT >= 31
                && Manifest.permission.BLUETOOTH_SCAN.equals(p)) return "Bluetooth scan";
        if (Build.VERSION.SDK_INT >= 33) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(p))  return "Notifications";
            if (Manifest.permission.NEARBY_WIFI_DEVICES.equals(p)) return "Nearby Wi-Fi";
        }
        return p;
    }

    /** Why we want it, in the terms of what breaks without it. */
    public static String why(String p) {
        if (Manifest.permission.READ_PHONE_STATE.equals(p)) {
            return "The serving cell: its identity, band, channel and the neighbour "
                 + "list. Without it the live page has no radio to show.";
        }
        if (Manifest.permission.ACCESS_FINE_LOCATION.equals(p)) {
            return "Every reading is stored with the position it was taken at, which "
                 + "is what makes a survey a map rather than a list. Android also "
                 + "gates Wi-Fi and Bluetooth scan results behind this, so without it "
                 + "those sweeps come back empty.";
        }
        if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(p)) {
            return "Asked alongside precise location because the platform pairs them.";
        }
        if (Build.VERSION.SDK_INT >= 31
                && Manifest.permission.BLUETOOTH_SCAN.equals(p)) {
            return "The BLE sweep, for the survey log and the detector.";
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(p)) {
                return "The ongoing readout while recording with the screen off. "
                     + "Denying it does not stop recording, it only hides it.";
            }
            if (Manifest.permission.NEARBY_WIFI_DEVICES.equals(p)) {
                return "The Wi-Fi sweep. Separate from location since Android 13.";
            }
        }
        return "";
    }
}

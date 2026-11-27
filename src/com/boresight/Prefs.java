package com.boresight;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Every user-controlled knob in one place. Defaults are deliberately conservative:
 * collection off, full precision only once the user opts in, nothing leaves the device
 * unless they export it themselves.
 */
public final class Prefs {

    private static final String FILE = "settings";

    // --- collection -------------------------------------------------------
    public static final String WARDRIVE_ON    = "wardrive_on";
    public static final String INTERVAL_MS    = "interval_ms";
    public static final String LOG_CELLS      = "log_cells";
    public static final String LOG_WIFI       = "log_wifi";
    public static final String LOG_BLE        = "log_ble";
    public static final String LOG_NEIGHBOURS = "log_neighbours";
    public static final String MOVING_ONLY    = "moving_only";
    public static final String MOVING_MPS     = "moving_mps";
    public static final String MIN_ACCURACY_M = "min_accuracy_m";

    // --- privacy ----------------------------------------------------------
    /** 0 = full, 3 = ~110 m, 2 = ~1.1 km. Decimal places kept on lat/lon. */
    public static final String COORD_PLACES   = "coord_places";
    /** none | hash | full */
    public static final String MAC_MODE       = "mac_mode";
    public static final String ZONE_LAT       = "zone_lat";
    public static final String ZONE_LON       = "zone_lon";
    public static final String ZONE_RADIUS_M  = "zone_radius_m";
    public static final String ZONE_ON        = "zone_on";
    public static final String RETAIN_DAYS    = "retain_days";

    // --- detection --------------------------------------------------------
    public static final String FLOCK_ON       = "flock_on";
    public static final String FLOCK_ALERT    = "flock_alert";

    // --- alerting ---------------------------------------------------------
    /** Audible alert on a detection. On by default: the screen is not being watched. */
    public static final String ALERT_SOUND    = "alert_sound";
    public static final String ALERT_VIBRATE  = "alert_vibrate";
    /** 0-100, passed to ToneGenerator. */
    public static final String ALERT_VOLUME   = "alert_volume";
    /** Also alert when approaching a camera already on the map, not just a live match. */
    public static final String ALERT_KNOWN    = "alert_known";
    /** How far ahead a known camera triggers, in metres. */
    public static final String ALERT_RANGE_M  = "alert_range_m";

    // --- known cameras ----------------------------------------------------
    /** Draw imported OpenStreetMap camera positions on the map. */
    public static final String L_ALPR         = "l_alpr";
    /** Keep the camera layer loaded as the drive leaves the area already fetched. */
    public static final String ALPR_AUTO      = "alpr_auto";
    /** Centre and time of the last automatic fetch, so we know when to ask again. */
    public static final String ALPR_SEEN_LAT  = "alpr_seen_lat";
    public static final String ALPR_SEEN_LON  = "alpr_seen_lon";
    public static final String ALPR_SEEN_AT   = "alpr_seen_at";

    // --- survey noise -----------------------------------------------------
    /**
     * Drop vehicle and carry-with-you radios from the Wi-Fi and BLE log. On by default:
     * they are seen at every point on a drive, so they are the densest thing in the log
     * and the least informative. Never applied to detection — see Noise.
     */
    public static final String DROP_VEHICLE   = "drop_vehicle";
    /** Extra user patterns, one regex per line. */
    public static final String NOISE_EXTRA    = "noise_extra";

    // --- nfc --------------------------------------------------------------
    /** Extra MIFARE keys, one 12-digit hex key per line, tried after the built-ins. */
    public static final String NFC_KEYS       = "nfc_keys";
    /** Text or URL this phone presents when a reader treats it as a tag. */
    public static final String NFC_EMULATE    = "nfc_emulate";

    // --- live view --------------------------------------------------------
    /** How long a cell stays listed after it drops out of the scan, in ms. */
    public static final String LINGER_MS      = "linger_ms";
    public static final String SKIN_ID        = "skin_id";
    public static final String REFRESH_MS     = "refresh_ms";
    public static final String SPARK_ROWS     = "spark_rows";

    // --- notification / widget -------------------------------------------
    public static final String NOTIF_ON       = "notif_on";
    public static final String NOTIF_GRAPH    = "notif_graph";
    public static final String ICON_VALUE     = "icon_value";   // rsrp | bars | none

    // --- walk test --------------------------------------------------------
    /** Rendered plan to reopen on launch, so a site survey resumes where it was. */
    public static final String LAST_PLAN      = "last_plan";
    public static final String LAST_PLAN_NAME = "last_plan_name";
    public static final String LAST_PLAN_PPM  = "last_plan_ppm";

    // --- first run --------------------------------------------------------
    /** The welcome page has been seen and dismissed. Set only by pressing Start. */
    public static final String ONBOARDED      = "onboarded";

    // --- menu -------------------------------------------------------------
    /** Which drawer profile is shown: 0 DAS, 1 Simple, 2 Everything. */
    public static final String MENU_VIEW      = "menu_view";
    /** Menu groups the user has opened, one per line. Survives leaving a screen. */
    public static final String MENU_OPEN      = "menu_open";

    // --- session log ------------------------------------------------------
    /** Filter the session log to LTE on DAS band families. On by default. */
    public static final String LOG_DAS_ONLY   = "log_das_only";

    // --- sync -------------------------------------------------------------
    /** Persisted SAF tree the survey is pushed to. Absent until the user grants one. */
    /**
     * Named radios held back from a bundle, one per line.
     *
     * Deliberately not the same list as NOISE_EXTRA. That one stops a radio being
     * recorded at all; this one keeps it in your own survey and leaves it out of what
     * you hand to somebody else. Your car belongs in your log and in nobody else's.
     */
    public static final String SYNC_EXCLUDE   = "sync_exclude";

    public static final String SYNC_TREE      = "sync_tree";
    public static final String SYNC_AUTO      = "sync_auto";
    public static final String SYNC_LAST      = "sync_last";
    /** Random, local, never derived from hardware — see Sync.deviceId. */
    public static final String DEVICE_ID      = "device_id";
    public static final String DEVICE_NAME    = "device_name";

    /**
     * Read paths the app itself is refused through the privileged backend instead of
     * reporting them as unreadable. On by default: when Shizuku is absent the fallback
     * never fires anyway, so the only reason to turn it off is to see the bare sandbox.
     */
    public static final String BACKEND_READS  = "backend_reads";

    // --- driving view -----------------------------------------------------
    public static final String HUD_IMPERIAL   = "hud_imperial";
    public static final String HUD_KEEP_AWAKE = "hud_keep_awake";

    // --- map layers -------------------------------------------------------
    public static final String L_TILES        = "l_tiles";
    public static final String L_TRACK        = "l_track";
    public static final String L_CELLS        = "l_cells";
    public static final String L_NEIGHBOURS   = "l_neighbours";
    public static final String L_WIFI         = "l_wifi";
    public static final String L_FLOCK        = "l_flock";
    public static final String L_HEAT         = "l_heat";
    public static final String L_LABELS       = "l_labels";

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean bool(Context c, String k, boolean def) {
        return get(c).getBoolean(k, def);
    }

    public static int integer(Context c, String k, int def) {
        return get(c).getInt(k, def);
    }

    public static String string(Context c, String k, String def) {
        return get(c).getString(k, def);
    }

    public static float real(Context c, String k, float def) {
        return get(c).getFloat(k, def);
    }

    public static void put(Context c, String k, boolean v) { get(c).edit().putBoolean(k, v).apply(); }
    public static void put(Context c, String k, int v)     { get(c).edit().putInt(k, v).apply(); }
    public static void put(Context c, String k, String v)  { get(c).edit().putString(k, v).apply(); }
    public static void put(Context c, String k, float v)   { get(c).edit().putFloat(k, v).apply(); }

    // ------------------------------------------------------------------ typed

    public static long interval(Context c) {
        return Math.max(1000, integer(c, INTERVAL_MS, 5000));
    }

    /** Linger window for cells that have dropped out of the current scan. */
    public static long linger(Context c) {
        return Math.max(0, integer(c, LINGER_MS, 60_000));
    }

    public static long refresh(Context c) {
        return Math.max(60, integer(c, REFRESH_MS, 100));
    }

    /** Selectable linger windows, paired with their labels. */
    public static final int[] LINGER_CHOICES = {
            0, 30_000, 60_000, 120_000, 180_000, 300_000, 600_000
    };
    public static final String[] LINGER_LABELS = {
            "off", "30 s", "1 min", "2 min", "3 min", "5 min", "10 min"
    };

    public static String lingerLabel(Context c) {
        long v = linger(c);
        for (int i = 0; i < LINGER_CHOICES.length; i++) {
            if (LINGER_CHOICES[i] == v) return LINGER_LABELS[i];
        }
        return (v / 1000) + " s";
    }

    public static int coordPlaces(Context c) {
        return integer(c, COORD_PLACES, 0);          // full precision by default
    }

    /** Apply the chosen coordinate precision. 0 keeps the value untouched. */
    public static double blur(Context c, double v) {
        int places = coordPlaces(c);
        if (places <= 0) return v;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    public static boolean inPrivateZone(Context c, double lat, double lon) {
        if (!bool(c, ZONE_ON, false)) return false;
        double zlat = real(c, ZONE_LAT, Float.NaN);
        double zlon = real(c, ZONE_LON, Float.NaN);
        if (Double.isNaN(zlat) || Double.isNaN(zlon)) return false;
        float[] out = new float[1];
        android.location.Location.distanceBetween(lat, lon, zlat, zlon, out);
        return out[0] <= integer(c, ZONE_RADIUS_M, 250);
    }

    /** Honour the MAC storage setting. Returns null when MACs must not be stored. */
    public static String mac(Context c, String bssid) {
        String mode = string(c, MAC_MODE, "full");
        if (bssid == null) return null;
        if ("none".equals(mode)) return null;
        if ("hash".equals(mode)) return "#" + Integer.toHexString(bssid.toLowerCase().hashCode());
        return bssid;
    }

    private Prefs() {}
}

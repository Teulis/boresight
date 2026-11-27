package com.boresight;

import android.content.Intent;
import android.location.Location;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Recording: the CellMapper-style survey log, and every switch that governs it.
 *
 * Recording is off by default and stays off until turned on here or from the notification.
 * The master switch is the first thing on the screen and the state is echoed in the
 * notification, the widget, the status strip and the map, so it is never ambiguous whether
 * the phone is writing a track.
 */
public class WardriveActivity extends Screen {

    private TextView cellCount, apCount, bleCount, hitCount, sessionCount, fixLine, riderLine;

    @Override protected String title() { return "RECORDING"; }

    @Override protected void content() {
        final Store store = Store.of(this);

        // --- master switch -------------------------------------------------
        LinearLayout master = pane("Survey log");
        toggle(master, "Record sightings",
                "Off by default. Writes cells, and optionally Wi-Fi and BLE, against your "
              + "position. Nothing is uploaded.",
                Prefs.WARDRIVE_ON, false, new Runnable() {
            @Override public void run() {
                if (Prefs.bool(WardriveActivity.this, Prefs.WARDRIVE_ON, false)) {
                    MonitorService.start(WardriveActivity.this);
                    toast("Recording on");
                } else {
                    toast("Recording off");
                }
                counts();
            }
        });
        toggle(master, "Keep running in the background",
                "A foreground service with an ongoing notification. Required for the log "
              + "to continue while the screen is off.",
                "svc_wanted", false, new Runnable() {
            @Override public void run() {
                if (Prefs.bool(WardriveActivity.this, "svc_wanted", false)) {
                    MonitorService.start(WardriveActivity.this);
                } else {
                    MonitorService.stop(WardriveActivity.this);
                }
            }
        });

        // --- counts --------------------------------------------------------
        LinearLayout stats = pane("Collected so far");
        cellCount    = kv(stats, "Cell sightings", "-");
        apCount      = kv(stats, "Wi-Fi sightings", "-");
        bleCount     = kv(stats, "BLE sightings", "-");
        hitCount     = kv(stats, "Detections", "-");
        sessionCount = kv(stats, "Sessions", "-");
        fixLine      = kv(stats, "Current fix", "-");
        LinearLayout srow = actionRow(stats);
        smallAction(srow, "Map", new Runnable() {
            @Override public void run() { startActivity(new Intent(WardriveActivity.this, MapActivity.class)); }
        });
        smallAction(srow, "History", new Runnable() {
            @Override public void run() { startActivity(new Intent(WardriveActivity.this, HistoryActivity.class)); }
        });
        smallAction(srow, "Refresh", new Runnable() {
            @Override public void run() { counts(); }
        });

        // --- what to log ---------------------------------------------------
        LinearLayout what = pane("What to log");
        toggle(what, "Cells", "The serving cell on every sample.", Prefs.LOG_CELLS, true, null);
        toggle(what, "Neighbour cells",
                "Also log the cells the modem is measuring but not using. Far more rows, "
              + "and the ones that make a tower estimate possible.",
                Prefs.LOG_NEIGHBOURS, true, null);
        toggle(what, "Wi-Fi access points",
                "Scanned roughly every 30 seconds — the platform throttles faster than that.",
                Prefs.LOG_WIFI, false, null);
        toggle(what, "Bluetooth LE",
                "Short low-power scan windows. Costs battery.",
                Prefs.LOG_BLE, false, null);

        // --- noise ---------------------------------------------------------
        LinearLayout noise = pane("Survey noise");
        hint(noise, "A survey records what was at a place. Your own car, your phone mount, "
                  + "your dash cam and anything in your bag are at every place you went, "
                  + "so they are the densest thing in the log and say nothing about any of "
                  + "it. Detection is never filtered — a camera on a pole cannot ride "
                  + "with you.");
        toggle(noise, "Drop radios that ride with you",
                Noise.rollingRule(),
                Prefs.DROP_VEHICLE, true, null);
        riderLine = kv(noise, "Caught this session", "-");
        action(noise, "Pick from what you've recorded", new Runnable() {
            @Override public void run() { pickRiders(store); }
        });
        action(noise, "My ignore list", new Runnable() {
            @Override public void run() { ignoreList(); }
        });
        action(noise, "Clean rows already recorded", new Runnable() {
            @Override public void run() { sweep(store); }
        });

        // --- interval ------------------------------------------------------
        LinearLayout rate = pane("Rate");
        choice(rate, "Log interval",
                "How often a sample is written. The screen updates faster than this; "
              + "this only governs the log.",
                Prefs.INTERVAL_MS, 5000,
                new int[]{1000, 2000, 3000, 5000, 10_000, 15_000, 30_000, 60_000},
                new String[]{"1 s", "2 s", "3 s", "5 s", "10 s", "15 s", "30 s", "1 min"},
                null);
        choice(rate, "Screen refresh", "How often the live readouts update.",
                Prefs.REFRESH_MS, 100,
                new int[]{60, 100, 150, 250, 500, 1000},
                new String[]{"16/s", "10/s", "7/s", "4/s", "2/s", "1/s"}, null);
        choice(rate, "Discard fixes worse than",
                "Nothing is logged while the position is this uncertain.",
                Prefs.MIN_ACCURACY_M, 50,
                new int[]{0, 10, 20, 30, 50, 100, 200},
                new String[]{"no limit", "10 m", "20 m", "30 m", "50 m", "100 m", "200 m"},
                null);
        toggle(rate, "Only while moving",
                "Skips sampling while stationary, so parking somewhere does not fill the "
              + "log with the same rows.",
                Prefs.MOVING_ONLY, false, null);

        // --- privacy -------------------------------------------------------
        LinearLayout priv = pane("Privacy");
        hint(priv, "This log is a record of where you have been. It never leaves the phone "
                 + "on its own — there is no network code in the recording path at all — "
                 + "but it is still worth keeping deliberately.");
        choice(priv, "Coordinate precision",
                "Round positions before they are stored. Lower precision still maps a "
              + "route usefully but stops the log pinpointing a doorway.",
                Prefs.COORD_PLACES, 0,
                new int[]{0, 4, 3, 2},
                new String[]{"full", "~11 m", "~110 m", "~1.1 km"},
                null);
        choiceStr(priv, "Store MAC addresses",
                "Wi-Fi and BLE hardware addresses identify a device, and sometimes a "
              + "person. Hashing keeps repeat sightings linkable without keeping the "
              + "address itself.",
                Prefs.MAC_MODE, "full",
                new String[]{"full", "hash", "none"},
                new String[]{"store in full", "store a hash", "do not store"},
                null);
        toggle(priv, "Private zone",
                "Log nothing within a radius of one chosen spot. Set it below.",
                Prefs.ZONE_ON, false, null);
        choice(priv, "Private zone radius", null,
                Prefs.ZONE_RADIUS_M, 250,
                new int[]{100, 150, 250, 400, 800, 1500},
                new String[]{"100 m", "150 m", "250 m", "400 m", "800 m", "1.5 km"},
                null);
        action(priv, "Set private zone to where I am now", new Runnable() {
            @Override public void run() {
                Location l = MonitorService.lastFix();
                if (l == null) { toast("No fix yet — start monitoring first"); return; }
                Prefs.put(WardriveActivity.this, Prefs.ZONE_LAT, (float) l.getLatitude());
                Prefs.put(WardriveActivity.this, Prefs.ZONE_LON, (float) l.getLongitude());
                Prefs.put(WardriveActivity.this, Prefs.ZONE_ON, true);
                toast("Private zone set here");
                counts();
            }
        });
        choice(priv, "Delete rows older than",
                "Applied each time the service starts.",
                Prefs.RETAIN_DAYS, 0,
                new int[]{0, 7, 30, 90, 180, 365},
                new String[]{"keep everything", "7 days", "30 days", "90 days",
                             "180 days", "a year"},
                null);

        // --- export --------------------------------------------------------
        LinearLayout out = pane("Export");
        hint(out, "Written to your Downloads folder. This is the only way data leaves "
                + "the phone, and it only happens when you press one of these.");
        LinearLayout erow = actionRow(out);
        smallAction(erow, "CSV", new Runnable() {
            @Override public void run() {
                Export.Result r = Export.csv(WardriveActivity.this, store.recent(200_000));
                report(r);
            }
        });
        smallAction(erow, "GeoJSON", new Runnable() {
            @Override public void run() {
                Export.Result r = Export.geojson(WardriveActivity.this,
                        store.recent(200_000), store.hits(5000));
                report(r);
            }
        });
        smallAction(erow, "WiGLE", new Runnable() {
            @Override public void run() {
                Export.Result r = Export.wigle(WardriveActivity.this, store.recent(200_000));
                report(r);
            }
        });

        // --- destructive ---------------------------------------------------
        LinearLayout wipe = pane("Delete");
        danger(wipe, "Strip all positions from the log",
                "Keeps every cell and signal reading but removes the coordinates, so the "
              + "log stops being a record of where you went. This cannot be undone.",
                new Runnable() {
            @Override public void run() { store.wipeLocations(); toast("Positions removed"); counts(); }
        });
        danger(wipe, "Delete the entire log",
                "Removes every sighting, session and detection. This cannot be undone.",
                new Runnable() {
            @Override public void run() { store.wipe(); toast("Log deleted"); counts(); }
        });
    }

    /**
     * The user's own names to ignore, one per line.
     *
     * This list was read by the filter from the day it was written and there was no way to
     * put anything in it, so the only names ever filtered were the ones compiled into the
     * app. A car's Bluetooth name is specific to its owner and does not belong in anyone
     * else's build; it belongs here.
     */
    /**
     * Offer the radios that actually travelled with you, so the ignore list can be
     * filled by tapping rather than by remembering what your car calls itself.
     *
     * The list already worked; nobody could use it. Filtering your own car meant
     * knowing its Bluetooth name exactly, which nobody does — it is on a screen in the
     * infotainment system you have not opened since you bought it. The survey already
     * knows, because the thing is in every other row: ranked by how far it travelled
     * with the phone, the car and the kit in the bag are the top entries and the fixed
     * access points are not there at all.
     */
    private void pickRiders(final Store store) {
        final List<Store.Rider> found = store.ridersSeen(12);
        final Sheet s = Sheet.of(this, "Riding with you");
        if (found.isEmpty()) {
            s.text("Nothing in the log has travelled far enough to look like it is "
                 + "riding with you. Record a drive first — this ranks radios by how "
                 + "far they moved with the phone, and anything bolted to a building "
                 + "cannot move at all.");
            s.close("Close").show();
            return;
        }
        s.text("Ranked by how far each one travelled with the phone. A fixed radio "
             + "cannot travel, so everything here is in the vehicle or in your bag. "
             + "Tap to add it to your ignore list.");
        for (final Store.Rider r : found) {
            String km = r.spanM >= 1000
                    ? String.format(Locale.US, "%.0f km", r.spanM / 1000)
                    : Math.round(r.spanM) + " m";
            s.choice(r.name, r.rows + " rows   ·   " + km + " of road", false,
                    new Runnable() {
                @Override public void run() { addToIgnore(r.name); }
            });
        }
        s.close("Close").show();
    }

    /** Append one name to the ignore list, without disturbing what is already there. */
    private void addToIgnore(String name) {
        String now = Prefs.string(this, Prefs.NOISE_EXTRA, "");
        for (String line : now.split("\n")) {
            if (line.trim().equalsIgnoreCase(name.trim())) {
                toast("Already ignored");
                return;
            }
        }
        String next = now.trim().isEmpty() ? name : now.trim() + "\n" + name;
        Prefs.put(this, Prefs.NOISE_EXTRA, next);
        toast("Ignoring " + name);
        counts();
    }

    private void ignoreList() {
        final Sheet s = Sheet.of(this, "My ignore list");
        s.text("One per line. A plain name matches anywhere in a name, so \"mycar\" "
             + "covers mycar6 as well. Lines starting with # are notes.");
        s.input("e.g. my car's Bluetooth name",
                Prefs.string(this, Prefs.NOISE_EXTRA, ""), 4);
        s.text("These are matched in addition to the built-in list of factory "
             + "infotainment hotspots, TPMS sensors, OBD dongles, dash cams, earbuds and "
             + "trackers. Nothing here affects detection.");
        s.action("Save", true, new Runnable() {
            @Override public void run() {
                Prefs.put(WardriveActivity.this, Prefs.NOISE_EXTRA, s.typed());
                int n = Noise.userPatterns(WardriveActivity.this).size();
                toast(n == 1 ? "1 pattern saved" : n + " patterns saved");
                counts();
            }
        });
        s.close("Cancel");
        s.show();
    }

    /**
     * Apply the filter to rows that were written before the filter knew about them.
     *
     * Shown as a count first, and never run on its own. This deletes survey data, and the
     * one thing worse than clutter in a log is a cleanup the user did not get to read.
     */
    private void sweep(final Store store) {
        final Store.Sweep dry = store.noiseSweep(this, false);
        if (dry.rows == 0) {
            info("Nothing to clean",
                    "No recorded Wi-Fi or Bluetooth rows match the filter.\n\n"
                  + "Names are only matched where they were stored, so a device that "
                  + "never broadcast a name cannot be cleaned this way.");
            return;
        }
        StringBuilder b = new StringBuilder();
        b.append(dry.rows).append(" rows from ").append(dry.radios)
         .append(dry.radios == 1 ? " radio" : " radios").append("\n\n");
        for (String n : dry.names) b.append("  ").append(n).append('\n');
        if (dry.radios > dry.names.size()) {
            b.append("  ... and ").append(dry.radios - dry.names.size()).append(" more\n");
        }

        Sheet.of(this, "Clean recorded rows")
             .text("Every sighting of these radios would be deleted. Detections are not "
                 + "touched. This cannot be undone.")
             .mono(b.toString())
             .action("Delete " + dry.rows + " rows", true, new Runnable() {
                 @Override public void run() {
                     Store.Sweep done = store.noiseSweep(WardriveActivity.this, true);
                     toast(done.rows + " rows removed");
                     counts();
                 }
             })
             .close("Keep them")
             .show();
    }

    private void report(Export.Result r) {
        if (r.ok) info("Exported", r.rows + " rows written to\n\n" + r.where);
        else info("Export failed", r.error == null ? "Unknown error" : r.error);
    }

    @Override protected void onResume() {
        super.onResume();
        counts();
    }

    private void counts() {
        Store s = Store.of(this);
        cellCount.setText(s.count(Store.KIND_CELL) + "   ·   "
                + s.distinctCount(Store.KIND_CELL) + " distinct");
        apCount.setText(s.count(Store.KIND_WIFI) + "   ·   "
                + s.distinctCount(Store.KIND_WIFI) + " distinct");
        bleCount.setText(String.valueOf(s.count(Store.KIND_BLE)));
        hitCount.setText(String.valueOf(s.hitCount()));
        List<Store.Session> sess = s.sessions();
        sessionCount.setText(String.valueOf(sess.size()));

        if (riderLine != null) {
            int caught = MonitorService.riders().count();
            int kept = MonitorService.riders().suppressed();
            riderLine.setText(caught == 0
                    ? "none yet"
                    : caught + (caught == 1 ? " radio" : " radios") + "   ·   "
                      + kept + " sightings kept out");
        }

        Location l = MonitorService.lastFix();
        if (l == null) {
            fixLine.setText(MonitorService.running ? "waiting" : "service not running");
        } else {
            boolean blocked = Prefs.inPrivateZone(this, l.getLatitude(), l.getLongitude());
            fixLine.setText(String.format(java.util.Locale.US, "%.5f, %.5f  ±%.0f m%s",
                    l.getLatitude(), l.getLongitude(), l.getAccuracy(),
                    blocked ? "   (inside private zone)" : ""));
        }

        boolean rec = Prefs.bool(this, Prefs.WARDRIVE_ON, false);
        status(rec ? "RECORDING" : "not recording",
                MonitorService.running ? "service running" : "service stopped");
        if (statusLeft != null) {
            statusLeft.setTextColor(rec ? skin.accent(Skin.CH_ALERT) : skin.inkSecondary);
        }
    }
}

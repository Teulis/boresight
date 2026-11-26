package com.boresight;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Settings: appearance, the live view's memory, the status bar glyph, the assumptions
 * behind tower estimation, and what the app does with your data.
 */
public class SettingsActivity extends Screen {

    @Override protected String title() { return "SETTINGS"; }

    @Override protected void content() {
        skins();
        live();
        notifications();
        towers();
        mapping();
        access();
        about();
    }

    /** What the app may do with a privileged backend when one happens to be there. */
    private void access() {
        LinearLayout box = pane("Reading blocked paths");
        toggle(box, "Use the privileged backend for refused reads",
                "When a file or folder is closed to this app but open to the shell, "
              + "read it through Shizuku and show it, instead of reporting it as "
              + "unreadable. This is what makes other apps' Android/data folders, "
              + "/proc/stat and the system log appear as ordinary content.",
                Prefs.BACKEND_READS, true, null);
        hint(box, "Off shows you the bare sandbox — useful for seeing what the app "
                + "manages without help. With no Shizuku running the setting changes "
                + "nothing either way, because there is no backend to fall back to.");
        hint(box, "Writes are not covered by this switch. Changing a file always asks "
                + "first, whichever route it takes.");
    }

    // ------------------------------------------------------------------ skins

    private void skins() {
        LinearLayout box = pane("Skin");
        String active = Prefs.string(this, Prefs.SKIN_ID, "builtin.instrument");
        List<Skin> all = Skin.available(this);

        boolean first = true;
        for (final Skin s : all) {
            if (!first) box.addView(th.rule(this));
            first = false;
            box.addView(skinRow(s, s.id.equals(active)));
        }

        hint(box, "These read TMOG's own .tmogskin format, so a skin written for the "
                + "desktop app drives this one unchanged. Surfaces, ink, type sizes, "
                + "corner radii, the quadrant list and the per-channel meter colours are "
                + "all honoured; anything that only makes sense on a desktop is ignored "
                + "rather than treated as an error.");
        hint(box, "Drop a .tmogskin file, or a .tmogskin folder containing skin.json, into "
                + "either directory below and it appears in this list:");
        for (File d : Skin.skinDirs(this)) {
            TextView t = mono(box, d.getAbsolutePath(), 9, skin.inkSecondary);
            t.setTextIsSelectable(true);
        }
        hint(box, "The radio metrics are mapped onto TMOG's channel names, so a skin "
                + "colours this app sensibly without knowing anything about cellular: "
                + "RSRP takes the cpu colour, SINR gpu, RSRQ memory, neighbours network, "
                + "downlink disk, timing advance thermal.");
    }

    private View skinRow(final Skin s, boolean active) {
        LinearLayout box = th.column(this);
        box.setPadding(th.dp(2), th.dp(9), th.dp(2), th.dp(9));
        box.setBackground(th.ripple(th.solid(0x00000000, 0)));
        box.setClickable(true);

        LinearLayout top = th.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = th.text(this, s.name, 13, skin.inkPrimary);
        name.setTypeface(skin.face(false, Typeface.BOLD));
        top.addView(name, Theme.weight(1f));

        if (active) {
            TextView pill = th.num(this, "ACTIVE", 9, skin.windowFlat());
            pill.setTypeface(skin.face(true, Typeface.BOLD));
            pill.setPadding(th.dp(9), th.dp(3), th.dp(9), th.dp(3));
            pill.setBackground(th.solid(skin.accent(Skin.CH_RSRP), skin.badgeCornerRadius));
            top.addView(pill);
        }
        box.addView(top, Theme.wrap());

        StringBuilder sub = new StringBuilder();
        if (s.author != null && !s.author.isEmpty()) sub.append(s.author);
        sub.append(sub.length() > 0 ? "   ·   " : "").append(s.builtin ? "built in" : "file");
        TextView meta = th.num(this, sub.toString(), 9, skin.inkSecondary);
        meta.setPadding(0, th.dp(2), 0, 0);
        box.addView(meta);

        if (s.description != null && !s.description.isEmpty()) {
            TextView desc = th.text(this, s.description, 10,
                    Skin.alpha(skin.inkSecondary, 0.9f));
            desc.setPadding(0, th.dp(3), th.dp(6), 0);
            box.addView(desc);
        }
        if (!s.builtin && s.sourcePath != null && !s.sourcePath.isEmpty()) {
            TextView path = th.num(this, s.sourcePath, 8,
                    Skin.alpha(skin.inkSecondary, 0.75f));
            path.setPadding(0, th.dp(2), 0, 0);
            box.addView(path);
        }

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.put(SettingsActivity.this, Prefs.SKIN_ID, s.id);
                recreate();
            }
        });
        return box;
    }

    // ------------------------------------------------------------------ live view

    private void live() {
        LinearLayout box = pane("Live view");
        choice(box, "Keep dropped cells for",
                "The modem's neighbour list churns hard — a cell can appear for one sweep "
              + "and vanish for the next three. This is how long a cell stays listed after "
              + "it leaves the scan, fading as it ages, each one keeping its own signal "
              + "history while it is held.",
                Prefs.LINGER_MS, 60_000,
                Prefs.LINGER_CHOICES, Prefs.LINGER_LABELS, null);
        choice(box, "Screen refresh",
                "How often the readouts and traces update. Does not affect the survey log.",
                Prefs.REFRESH_MS, 100,
                new int[]{60, 100, 150, 250, 500, 1000},
                new String[]{"16/s", "10/s", "7/s", "4/s", "2/s", "1/s"}, null);
    }

    // ------------------------------------------------------------------ notification

    private void notifications() {
        LinearLayout box = pane("Notification and widget");
        toggle(box, "Graph in the expanded notification",
                "Draws the signal trace into the notification when you pull it open.",
                Prefs.NOTIF_GRAPH, true, null);
        choiceStr(box, "Status bar glyph",
                "The small icon is supplied as a bitmap and redrawn on every sample, so it "
              + "can carry the live value the way a now-playing chip does.",
                Prefs.ICON_VALUE, "rsrp",
                new String[]{"rsrp", "bars", "none"},
                new String[]{"the number", "signal bars", "plain icon"}, null);

        LinearLayout row = actionRow(box);
        smallAction(row, "Start service", new Runnable() {
            @Override public void run() {
                MonitorService.start(SettingsActivity.this);
                toast("Service started");
            }
        });
        smallAction(row, "Stop service", new Runnable() {
            @Override public void run() {
                MonitorService.stop(SettingsActivity.this);
                toast("Service stopped");
            }
        });

        hint(box, "The widget asks for both the home screen and the keyguard, but lock "
                + "screen widget support comes and goes between Android and One UI "
                + "versions. The quick settings tile is the dependable route from a locked "
                + "phone: the panel pulls down over the lock screen on every build, and "
                + "the tile carries the reading in its subtitle.");
    }

    // ------------------------------------------------------------------ towers

    private void towers() {
        LinearLayout box = pane("Tower estimation");
        choice(box, "Assumed radiated power",
                "Used only when the model cannot be fitted from measured data.",
                "eirp_dbm", 60,
                new int[]{50, 55, 60, 63, 66},
                new String[]{"50 dBm", "55 dBm", "60 dBm", "63 dBm", "66 dBm"}, null);
        choice(box, "Path loss exponent",
                "How fast signal falls off with distance. Two is free space; four is dense "
              + "clutter.",
                "pl_exponent_tenths", 30,
                new int[]{20, 25, 30, 35, 40, 45},
                new String[]{"2.0  free-ish", "2.5  open", "3.0  suburban",
                             "3.5  urban", "4.0  dense urban", "4.5  heavy clutter"},
                new Runnable() {
            @Override public void run() {
                int tenths = Prefs.integer(SettingsActivity.this, "pl_exponent_tenths", 30);
                Prefs.put(SettingsActivity.this, "pl_exponent", tenths / 10f);
            }
        });
        choice(box, "Hide fixes below",
                "Estimates come with a quality score out of a hundred. This is the "
              + "threshold for plotting one on the map.",
                "fix_min_quality", 25,
                new int[]{0, 15, 25, 40, 60},
                new String[]{"show everything", "15%", "25%", "40%", "60%"}, null);

        hint(box, "These two assumptions only matter for power-only estimates. Where "
                + "timing advance is available the range is measured rather than modelled, "
                + "the solver uses it in preference, and it will fit the power model from "
                + "the timing data instead of using these numbers at all. An estimate's "
                + "detail panel on the map says which happened.");
        hint(box, "Geometry matters more than either setting. Sightings along one straight "
                + "road constrain distance well and direction badly, which is why an "
                + "estimate is drawn as an ellipse rather than a pin.");
    }

    // ------------------------------------------------------------------ map

    private void mapping() {
        LinearLayout box = pane("Map");
        toggle(box, "Basemap tiles",
                "Off falls back to a graticule and a scale bar, which is the sensible "
              + "state once you are out of coverage and nothing new will download.",
                Prefs.L_TILES, true, null);
        hint(box, "Tiles are cached on disk, so a route surveyed once stays readable with "
                + "no signal. The cache size and the button to clear it live in the map "
                + "screen's own menu, since that screen owns the cache.");
    }

    // ------------------------------------------------------------------ about

    private void about() {
        LinearLayout box = pane("About");
        kv(box, "App", "Boresight");
        kv(box, "Version", Stamp.VERSION + "   ·   " + Stamp.COMMIT);
        action(box, "Show the welcome page again", new Runnable() {
            @Override public void run() {
                startActivity(new android.content.Intent(
                        SettingsActivity.this, WelcomeActivity.class));
            }
        });
        hint(box, "A cell survey instrument: live serving and neighbour readouts with "
                + "history, a recording log and map in the CellMapper mould, transmitter "
                + "position estimates from timing advance and received power, a GNSS sky "
                + "view, every sensor the phone exposes, and a signature detector for "
                + "surveillance gear that broadcasts its presence.");

        LinearLayout priv = pane("Privacy");
        hint(priv, "The app makes two kinds of outbound call and no others. Map tiles "
                 + "from OpenStreetMap, which show that server the area you are looking "
                 + "at. And an update check against the public release listing on "
                 + "GitHub, which happens only when you press the button on the Log "
                 + "screen — never on a timer, never at startup. Both carry a generic "
                 + "client string and nothing else: no identifiers, no account, no "
                 + "position, and no record of what you have surveyed.");
        hint(priv, "Everything else is local. There is no analytics code, no crash "
                 + "reporting and no network code anywhere in the recording path. Export "
                 + "is the only way data leaves this device, it writes to your Downloads "
                 + "folder, and it only runs when you press the button. The app's own "
                 + "log stays on the phone until you save or send it.");
        hint(priv, "The survey log is a record of where you have been. Coordinate "
                 + "precision, MAC storage, a private zone and a retention period are all "
                 + "on the Recording screen, along with buttons to strip every position "
                 + "out of the log or delete it entirely.");
    }
}

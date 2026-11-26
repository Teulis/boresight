package com.boresight;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The signature detector: rules, and what they have matched.
 *
 * The limits are the point of this screen, so they are stated on it rather than buried
 * here. A rule only ever sees what a device chooses to broadcast, and the whole table is
 * editable because a signature nobody has confirmed is a guess with a number next to it.
 */
public class FlockActivity extends Screen {

    private Detector detector;
    private LinearLayout hitsList, rulesList;
    private android.widget.TextView knownLine;

    @Override protected String title() { return "DETECTOR"; }

    @Override protected void content() {
        detector = new Detector(this);

        LinearLayout about = pane("What this actually does");
        hint(about, "It compares the names and MAC prefixes that nearby radios broadcast "
                  + "against a list of patterns. That is all it can do. A camera with its "
                  + "Wi-Fi and Bluetooth switched off, or renamed, is invisible to it, and "
                  + "an unrelated device whose name happens to match a pattern will be "
                  + "flagged.");
        hint(about, "The rules shipped below are community heuristics and none of them are "
                  + "verified. Name patterns are the weakest kind: they catch only gear "
                  + "that announces itself. The signal worth trusting is a MAC prefix you "
                  + "have confirmed yourself at a known installation — add those as you "
                  + "find them and they will outperform everything seeded here.");

        LinearLayout scan = pane("Scanning");
        toggle(scan, "Scan for signatures",
                "Runs the Wi-Fi and BLE sweeps needed to test the rules, whether or not "
              + "you are logging those radios.",
                Prefs.FLOCK_ON, false, new Runnable() {
            @Override public void run() {
                if (Prefs.bool(FlockActivity.this, Prefs.FLOCK_ON, false)) {
                    MonitorService.start(FlockActivity.this);
                    toast("Scanning on");
                } else {
                    toast("Scanning off");
                }
                refreshStatus();
                // The empty-state line quotes this very setting, so it goes stale the
                // moment the switch moves: the card went on reading "scanning is off"
                // with scanning plainly on two inches above it.
                rebuildHits();
            }
        });
        toggle(scan, "Notify on a match",
                "A notification per new match. Repeat sightings of the same address in the "
              + "same place are suppressed.",
                Prefs.FLOCK_ALERT, true, null);
        hint(scan, "Matches are written to the survey log and plotted on the map alongside "
                 + "everything else, so they are covered by the same privacy settings — "
                 + "coordinate precision, the private zone, and MAC storage mode.");

        // ---------------------------------------------------------- known cameras
        LinearLayout known = pane("Known cameras");
        hint(known, "Positions other people have already recorded, from OpenStreetMap. "
                  + "This is the part that works: a camera has to be broadcasting for "
                  + "the rules above to see it, and most are not — a drive past dozens "
                  + "typically yields one radio match. A recorded position needs the "
                  + "camera to do nothing at all.");
        knownLine = th.text(this, "", 12, skin.inkSecondary);
        known.addView(knownLine);

        LinearLayout krow = actionRow(known);
        smallAction(krow, "Load around me", new Runnable() {
            @Override public void run() { fetchCameras(25); }
        });
        smallAction(krow, "Wider area", new Runnable() {
            @Override public void run() { fetchCameras(60); }
        });
        smallAction(krow, "Clear", new Runnable() {
            @Override public void run() {
                Alpr.clear(FlockActivity.this);
                refreshKnown();
                toast("Cleared");
            }
        });
        toggle(known, "Alert when one is coming up",
                "A softer double-beep, distinct from a live radio match, for a camera "
              + "ahead of you on the map. Only fires for ones in front while moving.",
                Prefs.ALERT_KNOWN, true, null);
        hint(known, "Data © OpenStreetMap contributors, ODbL 1.0. A pin is a lead, not a "
                  + "confirmed camera: it can be stale, mistagged, or simply wrong.");

        LinearLayout sound = pane("Alerts");
        toggle(sound, "Audible alert",
                "Two rising notes for a radio match, on the alarm stream so it stays "
              + "audible when navigation ducks the media volume.",
                Prefs.ALERT_SOUND, true, null);
        toggle(sound, "Vibrate", "A short buzz alongside the tone.",
                Prefs.ALERT_VIBRATE, true, null);
        hint(sound, "The same target will not speak twice inside 90 seconds. An alert "
                  + "that fires constantly gets muted, and a muted alert is worse than "
                  + "none because it is trusted and silent.");

        LinearLayout hitsPane = pane("Detections");
        hitsList = th.column(this);
        hitsPane.addView(hitsList, Theme.wrap());

        LinearLayout rulesPane = pane("Signature rules");
        hint(rulesPane, "Tap a rule to enable or disable it. A rule with an empty or "
                      + "malformed pattern never fires, whatever its switch says.");
        rulesList = th.column(this);
        rulesPane.addView(rulesList, Theme.wrap());

        LinearLayout row = actionRow(rulesPane);
        smallAction(row, "Add a rule", new Runnable() {
            @Override public void run() { addRule(); }
        });
        smallAction(row, "Export rules", new Runnable() {
            @Override public void run() {
                Export.Result r = Export.rules(FlockActivity.this, detector.rules());
                if (r.ok) info("Exported", r.rows + " rules written to\n\n" + r.where);
                else info("Export failed", r.error == null ? "Unknown error" : r.error);
            }
        });
        danger(rulesPane, "Reset to the seed list",
                "Discards every rule, including ones you added, and restores the shipped "
              + "set. This cannot be undone — export first if you want to keep yours.",
                new Runnable() {
            @Override public void run() {
                detector.resetToSeed();
                rebuildRules();
                toast("Rules reset");
            }
        });

        rebuildHits();
        rebuildRules();
        refreshKnown();
    }

    @Override protected void onResume() {
        super.onResume();
        if (detector == null) return;
        rebuildHits();
        refreshStatus();
        refreshKnown();
    }

    // ------------------------------------------------------------- known cameras

    private void refreshKnown() {
        if (knownLine == null) return;
        int n = Alpr.count(this);
        long when = Alpr.lastFetched(this);
        knownLine.setText(n == 0
                ? "None loaded yet."
                : n + " camera" + (n == 1 ? "" : "s") + " on file  ·  last loaded "
                  + Sync.fmt(when));
    }

    /** Pull every ALPR node within a radius of the current fix. */
    private void fetchCameras(final int km) {
        android.location.Location l = MonitorService.lastFix();
        if (l == null) {
            try {
                android.location.LocationManager lm = (android.location.LocationManager)
                        getSystemService(LOCATION_SERVICE);
                l = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                if (l == null) {
                    l = lm.getLastKnownLocation(
                            android.location.LocationManager.NETWORK_PROVIDER);
                }
            } catch (Throwable ignored) { }
        }
        if (l == null) {
            info("No position yet", "This needs a fix to know which area to ask for. "
                    + "Start recording, or open the driving view for a moment, then "
                    + "come back.");
            return;
        }
        final double lat = l.getLatitude(), lon = l.getLongitude();
        toast("Asking OpenStreetMap…");
        new Thread(new Runnable() {
            @Override public void run() {
                final Alpr.Fetch f = Alpr.fetchAround(FlockActivity.this, lat, lon, km);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshKnown();
                        if (!f.ok) {
                            info("Could not load", f.error);
                            return;
                        }
                        info("Loaded",
                                f.found + " camera" + (f.found == 1 ? "" : "s")
                              + " within " + km + " km.\n\n"
                              + f.added + " new, " + f.updated + " already known.\n\n"
                              + "They are on the map now, and will beep as you come up "
                              + "on them while recording.");
                    }
                });
            }
        }).start();
    }

    private void refreshStatus() {
        status(Theme.count(detector.enabledCount(), "rule") + " active",
                Theme.count(Store.of(this).hitCount(), "detection"));
    }

    // ------------------------------------------------------------------ detections

    private void rebuildHits() {
        hitsList.removeAllViews();
        List<Store.Hit> hits = Store.of(this).hits(300);
        if (hits.isEmpty()) {
            hint(hitsList, Prefs.bool(this, Prefs.FLOCK_ON, false)
                    ? "Nothing matched yet."
                    : "Nothing matched yet, and scanning is off.");
            refreshStatus();
            return;
        }
        long now = System.currentTimeMillis();
        boolean first = true;
        for (final Store.Hit h : hits) {
            if (!first) hitsList.addView(th.rule(this));
            first = false;
            hitsList.addView(hitRow(h, now));
        }
        refreshStatus();
    }

    private View hitRow(final Store.Hit h, long now) {
        LinearLayout box = th.column(this);
        box.setPadding(th.dp(2), th.dp(8), th.dp(2), th.dp(8));
        box.setBackground(th.ripple(th.solid(0x00000000, 0)));
        box.setClickable(true);

        LinearLayout top = th.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        String who = h.vendor == null || h.vendor.isEmpty() ? h.rule : h.vendor;
        TextView name = th.text(this, who == null ? "match" : who, 13, skin.inkPrimary);
        name.setTypeface(skin.face(false, Typeface.BOLD));
        top.addView(name, Theme.weight(1f));

        TextView conf = th.num(this, h.confidence + "%", 11,
                h.confidence >= 70 ? skin.accent(Skin.CH_ALERT) : skin.inkSecondary);
        conf.setTypeface(skin.face(true, Typeface.BOLD));
        top.addView(conf);
        box.addView(top, Theme.wrap());

        StringBuilder l2 = new StringBuilder();
        l2.append(h.name == null || h.name.isEmpty() ? "(no name broadcast)" : h.name);
        if (h.medium != null) l2.append("   ").append(h.medium);
        if (Sampler.has(h.rssi)) l2.append("   ").append(h.rssi).append(" dBm");
        TextView line2 = th.num(this, l2.toString(), 10, skin.inkSecondary);
        line2.setPadding(0, th.dp(2), 0, 0);
        box.addView(line2);

        StringBuilder l3 = new StringBuilder();
        l3.append(MainActivity.fmtAge(now - h.ts)).append(" ago");
        if (h.mac != null && !h.mac.isEmpty()) l3.append("   ").append(h.mac);
        if (!Double.isNaN(h.lat)) {
            l3.append("   ").append(String.format(Locale.US, "%.5f, %.5f", h.lat, h.lon));
        } else {
            l3.append("   no position");
        }
        TextView line3 = th.num(this, l3.toString(), 9,
                Skin.alpha(skin.inkSecondary, 0.85f));
        line3.setPadding(0, th.dp(2), 0, 0);
        box.addView(line3);

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                info("Detection", hitDetail(h));
            }
        });
        return box;
    }

    private String hitDetail(Store.Hit h) {
        StringBuilder b = new StringBuilder();
        b.append("rule        ").append(h.rule).append('\n');
        b.append("pattern     ").append(h.note == null ? "-" : h.note).append('\n');
        b.append("vendor      ").append(h.vendor == null || h.vendor.isEmpty()
                ? "-" : h.vendor).append('\n');
        b.append("confidence  ").append(h.confidence).append("%\n");
        b.append("name seen   ").append(h.name == null || h.name.isEmpty()
                ? "(none broadcast)" : h.name).append('\n');
        b.append("address     ").append(h.mac == null || h.mac.isEmpty()
                ? "(not stored)" : h.mac).append('\n');
        b.append("medium      ").append(h.medium == null ? "-" : h.medium).append('\n');
        b.append("signal      ").append(Sampler.has(h.rssi)
                ? h.rssi + " dBm" : "-").append('\n');
        if (!Double.isNaN(h.lat)) {
            b.append("position    ").append(String.format(Locale.US,
                    "%.6f, %.6f  ±%.0f m", h.lat, h.lon, h.acc)).append('\n');
        } else {
            b.append("position    no fix at the time\n");
        }
        b.append("when        ").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(h.ts)));
        b.append("\n\nA broadcast name or MAC prefix matched a pattern. That is a lead to "
               + "go and look at, not an identification.");
        return b.toString();
    }

    // ------------------------------------------------------------------ rules

    private void rebuildRules() {
        rulesList.removeAllViews();
        List<Detector.Rule> rules = detector.rules();
        if (rules.isEmpty()) {
            hint(rulesList, "No rules. Add one, or reset to the seed list.");
            return;
        }
        boolean first = true;
        for (final Detector.Rule r : rules) {
            if (!first) rulesList.addView(th.rule(this));
            first = false;
            rulesList.addView(ruleRow(r));
        }
        refreshStatus();
    }

    private View ruleRow(final Detector.Rule r) {
        final boolean blank = r.pattern == null || r.pattern.trim().isEmpty();
        // `bad` also covers a regex that would not compile, which is worth surfacing
        // separately from one the user simply left empty.
        final boolean broken = r.bad && !blank;
        final boolean effective = r.enabled && !r.bad;

        LinearLayout box = th.column(this);
        box.setPadding(th.dp(2), th.dp(8), th.dp(2), th.dp(8));
        box.setBackground(th.ripple(th.solid(0x00000000, 0)));
        box.setClickable(true);

        LinearLayout top = th.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names = th.column(this);
        TextView label = th.text(this, r.label == null ? "(unnamed)" : r.label, 13,
                effective ? skin.inkPrimary : skin.inkSecondary);
        label.setTypeface(skin.face(false, Typeface.BOLD));
        names.addView(label);

        StringBuilder meta = new StringBuilder();
        if (r.vendor != null && !r.vendor.isEmpty()) meta.append(r.vendor).append("   ");
        meta.append(r.kind);
        if (r.medium != null && !r.medium.isEmpty()) meta.append(" · ").append(r.medium);
        else meta.append(" · any medium");
        meta.append("   ").append(r.confidence).append('%');
        TextView sub = th.num(this, meta.toString(), 9, skin.inkSecondary);
        sub.setPadding(0, th.dp(1), th.dp(8), 0);
        names.addView(sub);
        top.addView(names, Theme.weight(1f));

        TextView pill;
        if (blank) {
            pill = th.num(this, "EMPTY", 9, skin.inkSecondary);
            pill.setBackground(skin.badgeBg(th, 0x00000000));
        } else if (broken) {
            pill = th.num(this, "INVALID", 9, skin.windowFlat());
            pill.setBackground(th.solid(skin.accent(Skin.CH_ALERT),
                    skin.badgeCornerRadius));
        } else if (r.enabled) {
            pill = th.num(this, "ON", 9, skin.windowFlat());
            pill.setBackground(th.solid(skin.accent(Skin.CH_RSRP),
                    skin.badgeCornerRadius));
        } else {
            pill = th.num(this, "OFF", 9, skin.inkSecondary);
            pill.setBackground(skin.badgeBg(th, 0x00000000));
        }
        pill.setTypeface(skin.face(true, Typeface.BOLD));
        pill.setPadding(th.dp(9), th.dp(3), th.dp(9), th.dp(3));
        top.addView(pill);
        box.addView(top, Theme.wrap());

        TextView pat = th.num(this, blank ? "no pattern set" : r.pattern, 10,
                blank || broken ? skin.accent(Skin.CH_ALERT) : skin.inkPrimary);
        pat.setPadding(0, th.dp(3), 0, 0);
        box.addView(pat);

        if (r.source != null && !r.source.isEmpty()) {
            TextView src = th.text(this, r.source, 9,
                    Skin.alpha(skin.inkSecondary, 0.85f));
            src.setPadding(0, th.dp(2), 0, 0);
            box.addView(src);
        }

        if (blank) {
            TextView why = th.text(this,
                    "Inactive until you give it a pattern. Long-press to delete.", 9,
                    Skin.alpha(skin.inkSecondary, 0.8f));
            why.setPadding(0, th.dp(2), 0, 0);
            box.addView(why);
        } else if (broken) {
            TextView why = th.text(this,
                    "Inactive: this pattern is not a valid expression.", 9,
                    skin.accent(Skin.CH_ALERT));
            why.setPadding(0, th.dp(2), 0, 0);
            box.addView(why);
        }

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (blank) { editPattern(r); return; }
                detector.setEnabled(r.id, !r.enabled);
                detector.invalidate();
                rebuildRules();
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                ruleMenu(r);
                return true;
            }
        });
        return box;
    }

    private void ruleMenu(final Detector.Rule r) {
        final String[] options = {"Edit pattern", "Set confidence", "Delete this rule"};
        Sheet s = Sheet.of(this, r.label == null ? "Rule" : r.label);
        for (int i = 0; i < options.length; i++) {
            final int which = i;
            s.choice(options[i], null, false, new Runnable() {
                @Override public void run() {
                    if (which == 0) editPattern(r);
                    else if (which == 1) editConfidence(r);
                    else deleteRule(r);
                }
            });
        }
        s.close("Cancel").show();
    }

    private void editPattern(final Detector.Rule r) {
        ask("Pattern  (" + r.kind + ")", r.pattern == null ? "" : r.pattern,
                new Consumer<String>() {
            @Override public void accept(String value) {
                r.pattern = value == null ? "" : value.trim();
                if (!r.pattern.isEmpty() && !r.enabled) r.enabled = true;
                detector.save(r);
                rebuildRules();
            }
        });
    }

    private void editConfidence(final Detector.Rule r) {
        ask("Confidence 1-100", String.valueOf(r.confidence), new Consumer<String>() {
            @Override public void accept(String value) {
                try {
                    int v = Integer.parseInt(value.trim());
                    r.confidence = Math.max(1, Math.min(100, v));
                    detector.save(r);
                    rebuildRules();
                } catch (Exception e) {
                    toast("Not a number");
                }
            }
        });
    }

    private void deleteRule(final Detector.Rule r) {
        Sheet.of(this, "Delete " + (r.label == null ? "this rule" : r.label) + "?")
                .text("The rule is removed from the detector.")
                .action("Delete", true, new Runnable() {
                    @Override public void run() {
                        detector.delete(r.id);
                        rebuildRules();
                        toast("Deleted");
                    }
                })
                .close("Cancel")
                .show();
    }

    private void addRule() {
        ask("Rule name", "", new Consumer<String>() {
            @Override public void accept(final String label) {
                if (label == null || label.trim().isEmpty()) return;
                final String[] kinds = {"ssid", "name", "oui"};
                final String[] choices = {
                        "ssid  —  Wi-Fi network name, matched as a regex",
                        "name  —  Bluetooth LE advertised name, as a regex",
                        "oui   —  MAC address prefix, e.g. a4:da:22"
                };
                Sheet s = Sheet.of(FlockActivity.this, "What should it match?");
                for (int i = 0; i < choices.length; i++) {
                    final int which = i;
                    s.choice(choices[i], null, false, new Runnable() {
                        @Override public void run() {
                            askPattern(label.trim(), kinds[which]);
                        }
                    });
                }
                s.close("Cancel").show();
            }
        });
    }

    private void askPattern(final String label, final String kind) {
        String seed = "oui".equals(kind) ? "a4:da:22"
                : "name".equals(kind) ? "(?i)flock" : "(?i)^flock";
        ask("Pattern for " + label, seed, new Consumer<String>() {
            @Override public void accept(String pattern) {
                if (pattern == null || pattern.trim().isEmpty()) {
                    toast("No pattern, no rule");
                    return;
                }
                Detector.Rule r = new Detector.Rule();
                r.label = label;
                r.vendor = "";
                r.kind = kind;
                r.pattern = pattern.trim();
                // A confirmed MAC prefix is worth testing on both radios; a name pattern
                // only makes sense on the one that carries that kind of name.
                r.medium = "oui".equals(kind) ? "" : ("name".equals(kind) ? "ble" : "wifi");
                r.confidence = 95;
                r.enabled = true;
                r.source = "added by you - confirmed locally";
                detector.save(r);
                rebuildRules();
                toast("Rule added");
            }
        });
    }
}

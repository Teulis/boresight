package com.boresight;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Switching off parts of the phone that cannot be switched off from Settings.
 *
 * All of it is `pm disable-user --user 0`, which is what the shell uid may do and what
 * Shizuku therefore lets this app do. Two properties make that the right tool here
 * rather than something heavier:
 *
 *  - It is per-user and reversible. `pm enable` puts the package back exactly as it
 *    was; nothing is deleted and no partition is touched.
 *  - It survives reboots but not a factory reset or a firmware update, so the worst
 *    case is that a thing you turned off comes back.
 *
 * Nothing here is done quietly. Each switch states what stops working, and the update
 * ones say plainly that security patches stop arriving too — that is the actual cost of
 * blocking OTA, and a tool that hides it is not being helpful.
 */
public class DeviceActivity extends Screen {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;

    private LinearLayout otaBox, telemetryBox, secBox;
    private TextView secVerdict;
    private Integrity.Attestation attestation = new Integrity.Attestation();
    private TextView backendNote;

    /** package, short name, what it does, what breaks. */
    private static final String[][] OTA = {
        {"com.wssyncmldm", "Software update (FOTA)",
         "The main Samsung over-the-air updater. This is the one that downloads "
       + "firmware in the background and nags to install it.",
         "Settings › Software update stops working. No firmware updates at all, "
       + "including monthly security patches."},
        {"com.sec.android.soagent", "SOAgent",
         "Samsung's second update agent, used for carrier and configuration updates "
       + "and some firmware paths.",
         "Carrier settings updates stop arriving."},
        {"com.samsung.sdm", "SDM",
         "Samsung Device Management — the carrier-side management client.",
         "The carrier can no longer push device management changes."},
        {"com.samsung.android.sdm.config", "SDM config",
         "Configuration half of the device management client.",
         "As above."},
        {"com.wsomacp", "OMA client provisioning",
         "Accepts over-the-air provisioning messages from the carrier — APNs, MMS "
       + "settings and similar, pushed as special SMS.",
         "You would have to enter APN changes by hand if the carrier changes them."},
        {"com.samsung.android.app.updatecenter", "Update centre",
         "Samsung app-update surface.",
         "Samsung apps stop updating themselves."},
        {"com.sprint.ce.updater", "Carrier updater",
         "A carrier-specific update client left on US firmware.",
         "Carrier-pushed updates stop."},
    };

    private static final String[][] TELEMETRY = {
        {"com.samsung.android.dqagent", "DQ agent",
         "Diagnostic Quality agent — collects device telemetry and sends it to "
       + "Samsung.",
         "Nothing you use. Diagnostics reporting stops."},
        {"com.samsung.android.rubin.app", "Customisation Service",
         "Samsung's on-device profiling that feeds app and content suggestions.",
         "Suggestions become less tailored. Nothing breaks."},
        {"com.sec.android.diagmonagent", "Diagnostic monitor",
         "Crash and usage reporting to Samsung.",
         "Nothing you use."},
        {"com.samsung.android.scloud", "Samsung Cloud sync",
         "Background sync to Samsung's cloud.",
         "Samsung Cloud backup and sync stop. Disable only if you do not use it."},
    };

    @Override protected String title() { return "DEVICE"; }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();

        LinearLayout head = pane("Package control");
        kv(head, "Device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        kv(head, "Build", android.os.Build.DISPLAY);
        kv(head, "Patch level", android.os.Build.VERSION.SECURITY_PATCH);
        backendNote = hint(head, "");

        LinearLayout orow = actionRow(head);
        smallAction(orow, "Re-check", new Runnable() {
            @Override public void run() { refresh(); }
        });
        smallAction(orow, "Backend", new Runnable() {
            @Override public void run() {
                Backend.show(DeviceActivity.this, 4242, new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        });
        smallAction(orow, "Enable all", new Runnable() {
            @Override public void run() { confirmAll(true); }
        });

        LinearLayout sec = pane("Security and integrity");
        secBox = th.column(this);
        sec.addView(secBox, Theme.wrap());
        secVerdict = hint(sec, "");
        LinearLayout srow = actionRow(sec);
        smallAction(srow, "Attest in hardware", new Runnable() {
            @Override public void run() { runAttestation(); }
        });
        smallAction(srow, "Root check", new Runnable() {
            @Override public void run() { rootCheck(); }
        });
        hint(sec, "What an integrity check sees. The properties are informative but "
                + "forgeable by anything with root; hardware attestation is not, which "
                + "is why banking apps rely on it. Both are shown, and labelled.");

        LinearLayout ota = pane("Updates");
        hint(ota, "Disabling these stops over-the-air updates. That includes the "
                + "monthly security patches, not only the feature updates — if you "
                + "block updates, patching becomes something you do deliberately by "
                + "flashing, or not at all. Everything here is reversible with Enable.");
        otaBox = th.column(this);
        ota.addView(otaBox, Theme.wrap());

        LinearLayout tel = pane("Telemetry and extras");
        hint(tel, "Background reporting and sync. Less consequential than the update "
                + "clients, and the same reversible switch.");
        telemetryBox = th.column(this);
        tel.addView(telemetryBox, Theme.wrap());

        LinearLayout other = pane("Other packages");
        hint(other, "Anything else on the device can be disabled the same way from "
                  + "here. The list is long, so it is searched rather than shown.");
        LinearLayout prow = actionRow(other);
        smallAction(prow, "Find a package…", new Runnable() {
            @Override public void run() { findPackage(); }
        });
        smallAction(prow, "List disabled", new Runnable() {
            @Override public void run() { listDisabled(); }
        });

        refresh();
        loadSecurity();
    }

    // ------------------------------------------------------------- integrity

    private void loadSecurity() {
        pool.execute(new Runnable() {
            @Override public void run() {
                final java.util.List<Integrity.Fact> facts = Integrity.boot(DeviceActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() { paintSecurity(facts); }
                });
            }
        });
    }

    private void paintSecurity(java.util.List<Integrity.Fact> facts) {
        secBox.removeAllViews();
        for (final Integrity.Fact f : facts) {
            LinearLayout row = th.row(this);
            row.setPadding(0, th.dp(4), 0, th.dp(4));
            row.setBackground(th.ripple(th.solid(0x00000000, 0)));
            row.setClickable(true);

            TextView k = th.text(this, f.name, 11, skin.inkSecondary);
            row.addView(k, new LinearLayout.LayoutParams(th.dp(150),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView v = th.num(this, f.value, 11,
                    f.grade > 0 ? skin.accent(Skin.CH_FREQ)
                    : f.grade < 0 ? skin.accent(Skin.CH_ALERT) : skin.inkPrimary);
            row.addView(v, new LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    if (!f.note.isEmpty()) Sheet.of(DeviceActivity.this, f.name)
                            .mono(f.value).rule().text(f.note).close("Close").show();
                }
            });
            secBox.addView(row, Theme.wrap());
        }
        int ks = Integrity.keystoreVersion(this);
        LinearLayout row = th.row(this);
        row.setPadding(0, th.dp(4), 0, th.dp(4));
        row.addView(th.text(this, "Hardware keystore", 11, skin.inkSecondary),
                new LinearLayout.LayoutParams(th.dp(150),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(th.num(this, (ks > 0 ? "KeyMint " + ks : "not reported")
                + (Integrity.hasStrongBox(this) ? "  ·  StrongBox present" : ""), 11,
                Integrity.hasStrongBox(this) ? skin.accent(Skin.CH_FREQ) : skin.inkPrimary),
                new LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        secBox.addView(row, Theme.wrap());

        secVerdict.setText(Integrity.verdict(facts, attestation));
    }

    /**
     * Generate an attested key and report what the secure element says.
     *
     * Off the main thread because StrongBox key generation genuinely takes a second
     * or two on some devices — it is a separate chip doing real work.
     */
    private void runAttestation() {
        status("attesting…", "");
        pool.execute(new Runnable() {
            @Override public void run() {
                final Integrity.Attestation a =
                        Integrity.attest(DeviceActivity.this, Integrity.hasStrongBox(DeviceActivity.this));
                attestation = a;
                ui.post(new Runnable() {
                    @Override public void run() {
                        status("", "");
                        StringBuilder b = new StringBuilder();
                        b.append("ran            ").append(a.ran ? "yes" : "no").append('\n');
                        b.append("security level ").append(a.securityLevel).append('\n');
                        b.append("hardware       ").append(a.hardwareBacked ? "yes" : "no").append('\n');
                        b.append("StrongBox      ").append(a.strongBox ? "yes" : "no").append('\n');
                        b.append("chain length   ").append(a.chainLength).append('\n');
                        if (!a.rootIssuer.isEmpty()) {
                            b.append("chains to      ").append(a.rootIssuer).append('\n');
                        }
                        if (a.error != null) b.append("error          ").append(a.error).append('\n');
                        Sheet.of(DeviceActivity.this, "Hardware attestation")
                                .mono(b.toString())
                                .rule()
                                .text(a.chainLength > 1
                                    ? "A chain of " + a.chainLength + " certificates came "
                                    + "back, rooted in a key burned into this device at "
                                    + "manufacture. Nothing running in Android can forge "
                                    + "that, which is why it is the evidence an integrity "
                                    + "check actually trusts — the properties above are "
                                    + "just conveniences."
                                    : "No real chain came back, so there is no hardware "
                                    + "statement about this device — only the properties, "
                                    + "which anything with root can set.")
                                .close("Close").show();
                        loadSecurity();
                    }
                });
            }
        });
    }

    private void rootCheck() {
        pool.execute(new Runnable() {
            @Override public void run() {
                final java.util.List<String> found = Integrity.rootSigns(DeviceActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        Sheet.of(DeviceActivity.this, "Root indicators")
                                .mono(found.isEmpty() ? "Nothing found in the obvious places."
                                        : join(found))
                                .rule()
                                .text("Worth being honest about this one: it looks in the "
                                    + "usual paths and for the usual packages, and every "
                                    + "root-hiding module defeats it. A positive result "
                                    + "means something; a clean one proves nothing. The "
                                    + "attestation above is the check that cannot be "
                                    + "talked out of.")
                                .close("Close").show();
                    }
                });
            }
        });
    }

    private static String join(java.util.List<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) b.append(x).append('\n');
        return b.toString();
    }

    @Override protected void onResume() {
        super.onResume();
        if (backendNote != null) refresh();
    }

    // ------------------------------------------------------------------ state

    /** installed, disabled — read in one pass so the UI is not N shell calls deep. */
    private static final class State {
        final java.util.HashSet<String> installed = new java.util.HashSet<>();
        final java.util.HashSet<String> disabled = new java.util.HashSet<>();
    }

    private void refresh() {
        final Shell sh = Shell.get(this);
        backendNote.setText(sh.privileged()
                ? "Running through the privileged backend, so these switches work."
                : "These need the shell uid, which this app does not have on its "
                + "own: " + Backend.summary(this) + ". Press Backend above for the "
                + "steps. Without it the states below are still read correctly, but "
                + "nothing can be changed.");

        pool.execute(new Runnable() {
            @Override public void run() {
                final State st = readState(sh);
                ui.post(new Runnable() {
                    @Override public void run() {
                        paint(otaBox, OTA, st);
                        paint(telemetryBox, TELEMETRY, st);
                    }
                });
            }
        });
    }

    private State readState(Shell sh) {
        State st = new State();
        Shell.Result all = sh.exec("pm list packages --user 0", 15);
        Shell.Result off = sh.exec("pm list packages -d --user 0", 15);
        collect(all, st.installed);
        collect(off, st.disabled);
        return st;
    }

    private static void collect(Shell.Result r, java.util.Set<String> into) {
        if (!r.ran || r.out == null) return;
        for (String line : r.out.split("\n")) {
            String s = line.trim();
            if (s.startsWith("package:")) into.add(s.substring(8).trim());
        }
    }

    private void paint(LinearLayout box, String[][] defs, State st) {
        box.removeAllViews();
        boolean priv = Shell.get(this).privileged();
        for (final String[] d : defs) {
            final String pkg = d[0];
            boolean installed = st.installed.contains(pkg);
            final boolean disabled = st.disabled.contains(pkg);

            LinearLayout row = th.column(this);
            row.setPadding(0, th.dp(9), 0, th.dp(9));

            TextView title = th.num(this, d[1], 13,
                    !installed ? skin.inkSecondary
                    : disabled ? skin.accent(Skin.CH_ALERT)
                               : skin.accent(Skin.CH_FREQ));
            title.setTypeface(skin.face(true, Typeface.BOLD));
            row.addView(title);

            TextView state = th.num(this,
                    !installed ? "not on this device"
                    : disabled ? "DISABLED" : "enabled", 10,
                    skin.inkSecondary);
            row.addView(state);

            row.addView(th.text(this, pkg, 9, Skin.alpha(skin.inkSecondary, 0.7f)));
            row.addView(th.text(this, d[2], 10, Skin.alpha(skin.inkSecondary, 0.95f)));

            if (installed) {
                LinearLayout act = actionRow(row);
                smallAction(act, disabled ? "Enable" : "Disable", new Runnable() {
                    @Override public void run() { confirm(d, !disabled); }
                });
                smallAction(act, "What breaks", new Runnable() {
                    @Override public void run() {
                        info(d[1], d[2] + "\n\nIf you disable it:\n" + d[3]
                                + "\n\nPackage: " + pkg
                                + "\n\nThis is per-user and reversible — Enable puts it "
                                + "back. A firmware update or factory reset also "
                                + "restores it.");
                    }
                });
                if (!priv) {
                    for (int i = 0; i < act.getChildCount(); i++) {
                        act.getChildAt(i).setAlpha(0.4f);
                    }
                }
            }

            View rule = new View(this);
            rule.setBackgroundColor(skin.inkRule);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, th.hair()));
            rlp.setMargins(0, th.dp(8), 0, 0);
            row.addView(rule, rlp);

            box.addView(row, Theme.wrap());
        }
    }

    // ------------------------------------------------------------------ actions

    private void confirm(final String[] d, final boolean disable) {
        if (!Shell.get(this).privileged()) {
            Backend.show(this, 4242, new Runnable() {
                @Override public void run() { refresh(); }
            });
            return;
        }
        Sheet.of(this, (disable ? "Disable " : "Enable ") + d[1] + "?")
                .text(disable
                        ? d[2] + "\n\nWhat stops working:\n" + d[3]
                        + "\n\nReversible: Enable puts it back."
                        : "Puts " + d[1] + " back exactly as it was.")
                .action(disable ? "Disable" : "Enable", true, new Runnable() {
                    @Override public void run() { apply(d[0], disable); }
                })
                .close("Cancel")
                .show();
    }

    private void apply(final String pkg, final boolean disable) {
        status(disable ? "disabling…" : "enabling…", pkg);
        pool.execute(new Runnable() {
            @Override public void run() {
                Shell sh = Shell.get(DeviceActivity.this);
                final Shell.Result r = sh.exec((disable
                        ? "pm disable-user --user 0 "
                        : "pm enable --user 0 ") + pkg, 20);
                ui.post(new Runnable() {
                    @Override public void run() {
                        status("", "");
                        String out = r.out == null ? "" : r.out.trim();
                        if (out.toLowerCase(java.util.Locale.US).contains("new state")) {
                            toast(out);
                        } else {
                            info("Did not take", out.isEmpty() ? "no output" : out);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    private void confirmAll(final boolean enable) {
        if (!Shell.get(this).privileged()) { toast("Needs the privileged backend"); return; }
        Sheet.of(this, "Enable everything listed?")
                .text("Re-enables every package on this screen that is currently "
                        + "disabled, including the update clients. Use this to undo.")
                .action("Enable all", true, new Runnable() {
                    @Override public void run() {
                        pool.execute(new Runnable() {
                            @Override public void run() {
                                Shell sh = Shell.get(DeviceActivity.this);
                                StringBuilder cmd = new StringBuilder();
                                for (String[] x : OTA)       cmd.append("pm enable --user 0 ").append(x[0]).append("; ");
                                for (String[] x : TELEMETRY) cmd.append("pm enable --user 0 ").append(x[0]).append("; ");
                                sh.exec(cmd.toString(), 60);
                                ui.post(new Runnable() {
                                    @Override public void run() { toast("Re-enabled"); refresh(); }
                                });
                            }
                        });
                    }
                })
                .close("Cancel")
                .show();
    }

    private void findPackage() {
        ask("Package name contains", "", new Consumer<String>() {
            @Override public void accept(final String q) {
                if (q == null || q.trim().isEmpty()) return;
                pool.execute(new Runnable() {
                    @Override public void run() {
                        Shell sh = Shell.get(DeviceActivity.this);
                        Shell.Result r = sh.exec("pm list packages --user 0 | grep -i "
                                + shellQuote(q.trim()), 15);
                        final List<String> found = new ArrayList<>();
                        if (r.ran && r.out != null) {
                            for (String l : r.out.split("\n")) {
                                String s = l.trim();
                                if (s.startsWith("package:")) found.add(s.substring(8));
                            }
                        }
                        ui.post(new Runnable() {
                            @Override public void run() { pickPackage(found); }
                        });
                    }
                });
            }
        });
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void pickPackage(final List<String> found) {
        if (found.isEmpty()) { toast("Nothing matched"); return; }
        final String[] arr = found.toArray(new String[0]);
        Sheet s = Sheet.of(this, found.size() + " matched");
        for (int i = 0; i < arr.length; i++) {
            final String pkg = arr[i];
            s.choice(pkg, null, false, new Runnable() {
                @Override public void run() {
                    // No curated description for an arbitrary package, so the warning
                    // is generic and deliberately blunt.
                    confirm(new String[]{ pkg, pkg,
                            "A package on this device.",
                            "Unknown — this one is not on the curated list, so "
                          + "nothing here can tell you what depends on it. "
                          + "Disabling a system package you do not recognise can "
                          + "break the phone until you enable it again." }, true);
                }
            });
        }
        s.close("Cancel").show();
    }

    private void listDisabled() {
        pool.execute(new Runnable() {
            @Override public void run() {
                Shell sh = Shell.get(DeviceActivity.this);
                Shell.Result r = sh.exec("pm list packages -d --user 0", 15);
                final StringBuilder b = new StringBuilder();
                int n = 0;
                if (r.ran && r.out != null) {
                    for (String l : r.out.split("\n")) {
                        String s = l.trim();
                        if (s.startsWith("package:")) { b.append(s.substring(8)).append('\n'); n++; }
                    }
                }
                final int count = n;
                ui.post(new Runnable() {
                    @Override public void run() {
                        info(count + " packages disabled for this user",
                                count == 0 ? "None." : b.toString());
                    }
                });
            }
        });
    }
}

package com.boresight;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place that explains the privileged backend and offers a way forward.
 *
 * This exists because the app was good at diagnosing and useless at helping: it would
 * say "Shizuku is not running" and stop, which is a dead end for anyone who does not
 * already know that Shizuku's service has to be started from Shizuku's own app, by
 * pairing over wireless debugging, every time the phone reboots.
 *
 * An app cannot start the service itself — that is the entire security model, and if
 * it could, the permission would be worthless. What it can do is take you to the exact
 * screen where it is done and say what to press, which is what this does.
 */
public final class Backend {

    private Backend() { }

    /** Where the user is in the sequence, which decides what to offer. */
    public static final int NOT_INSTALLED = 0, NOT_RUNNING = 1, NOT_GRANTED = 2, READY = 3;

    public static int state(Context c) {
        Shell.ShizukuExecutor z = Shell.get(c).shizuku();
        if (!z.installed()) return NOT_INSTALLED;
        if (!z.serviceRunning()) return NOT_RUNNING;
        if (!z.permitted()) return NOT_GRANTED;
        return READY;
    }

    /** One short line for a status row anywhere in the app. */
    public static String summary(Context c) {
        switch (state(c)) {
            case NOT_INSTALLED: return "not installed";
            case NOT_RUNNING:   return "installed, service not started";
            case NOT_GRANTED:   return "running, access not granted";
            default:            return "active, uid " + Shell.get(c).shizuku().remoteUid();
        }
    }

    /**
     * Show the status and the next step, with buttons that actually go somewhere.
     *
     * @param requestCode passed to Shizuku's permission request, so the caller's
     *                    onRequestPermissionsResult can refresh
     * @param after       run after anything that might have changed the state
     */
    public static void show(final Activity a, final int requestCode, final Runnable after) {
        final Shell sh = Shell.get(a);
        final Shell.ShizukuExecutor z = sh.shizuku();
        final int st = state(a);

        StringBuilder b = new StringBuilder();
        b.append("Backend in use   ").append(sh.backendDescription()).append("\n\n");
        b.append("Shizuku installed   ").append(z.installed() ? "yes" : "no").append('\n');
        b.append("Service started     ").append(z.serviceRunning() ? "yes" : "no").append('\n');
        b.append("Access granted      ").append(z.permitted() ? "yes" : "no").append('\n');
        if (z.serviceRunning()) {
            b.append("Runs as uid         ").append(z.remoteUid()).append('\n');
        }
        b.append('\n');

        final List<String> actions = new ArrayList<>();
        final List<Runnable> run = new ArrayList<>();

        switch (st) {
            case NOT_INSTALLED:
                b.append("Shizuku is not installed, and the app does not need it — "
                       + "every feature works on this app's own uid.\n\n"
                       + "Installing it widens the console, the file browser, the task "
                       + "manager and the package controls. It is a free, open-source "
                       + "app from RikkaApps.");
                actions.add("Get Shizuku from GitHub");
                run.add(new Runnable() {
                    @Override public void run() {
                        open(a, "https://github.com/RikkaApps/Shizuku/releases");
                    }
                });
                actions.add("Find it in the Play Store");
                run.add(new Runnable() {
                    @Override public void run() {
                        open(a, "https://play.google.com/store/apps/details?id="
                                + "moe.shizuku.privileged.api");
                    }
                });
                break;

            case NOT_RUNNING:
                b.append("Shizuku is installed but its service is not running, so "
                       + "there is nothing for this app to connect to.\n\n"
                       + "No app can start it for you — that restriction is the whole "
                       + "point of it. On a phone without root it is started by "
                       + "pairing over wireless debugging:\n\n"
                       + "1.  Turn on Wireless debugging in Developer options.\n"
                       + "2.  Open Shizuku and press Start.\n"
                       + "3.  Come back here and press Re-check.\n\n"
                       + "It does not survive a reboot, so this is a once-per-restart "
                       + "chore rather than a one-time setup.");
                actions.add("Open Shizuku");
                run.add(new Runnable() { @Override public void run() { openShizuku(a); } });
                actions.add("Developer options");
                run.add(new Runnable() { @Override public void run() { openDevOptions(a); } });
                break;

            case NOT_GRANTED:
                b.append("The service is running but has not given this app access. "
                       + "Press Grant and approve the prompt.");
                actions.add("Grant access");
                run.add(new Runnable() {
                    @Override public void run() {
                        z.requestPermission(requestCode);
                        Toast.makeText(a, "Approve the prompt", Toast.LENGTH_SHORT).show();
                    }
                });
                actions.add("Open Shizuku");
                run.add(new Runnable() { @Override public void run() { openShizuku(a); } });
                break;

            default:
                b.append("Active. Commands run as uid ").append(z.remoteUid())
                 .append(".\n\n/data/data stays closed even now — the shell is refused "
                       + "there too, so that one needs root rather than Shizuku.");
                actions.add("Open Shizuku");
                run.add(new Runnable() { @Override public void run() { openShizuku(a); } });
                break;
        }

        actions.add("Re-check");
        run.add(new Runnable() {
            @Override public void run() {
                Shell.get(a).forgetProbes();
                Vfs.reset();
                if (after != null) after.run();
                Toast.makeText(a, summary(a), Toast.LENGTH_SHORT).show();
            }
        });

        // Sheet, not AlertDialog: the platform dialog shows a message or a list and
        // silently drops the other, which is how this screen once explained the
        // problem at length and offered no button. It also matches the app's skin.
        Sheet s = Sheet.of(a, "Privileged backend").mono(b.toString());
        for (int i = 0; i < actions.size(); i++) {
            final Runnable r = run.get(i);
            final boolean closes = !"Re-check".equals(actions.get(i));
            s.action(actions.get(i), closes, new Runnable() {
                @Override public void run() { r.run(); }
            });
        }
        s.close("Close").show();
    }
    public static void openShizuku(Activity a) {
        try {
            Intent i = a.getPackageManager().getLaunchIntentForPackage(
                    "moe.shizuku.privileged.api");
            if (i == null) { Toast.makeText(a, "Shizuku is not installed", Toast.LENGTH_SHORT).show(); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(a, "Could not open Shizuku", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Developer options, where wireless debugging lives.
     *
     * There is no public intent for the wireless debugging page itself — measured on
     * this Samsung build, ADB_WIRELESS_SETTINGS does not resolve — so this lands on
     * the developer options list and the instruction above says what to look for.
     */
    public static void openDevOptions(Activity a) {
        String[] tries = {
                "android.settings.ADB_WIRELESS_SETTINGS",
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        };
        for (String action : tries) {
            try {
                a.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
            } catch (Throwable ignored) { }
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(a, "Developer options › Wireless debugging",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(a, "Could not open Settings", Toast.LENGTH_SHORT).show();
        }
    }

    private static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(a, url, Toast.LENGTH_LONG).show();
        }
    }
}

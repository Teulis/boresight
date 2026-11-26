package com.boresight;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * What a person sees the first time they open Boresight, and never again unless they
 * ask for it from Settings.
 *
 * It exists because the alternative is what was here before: six permission dialogs
 * fired back to back with no explanation, and then the live page, which is dense and
 * assumes you already know what RSRP is. Someone handed the phone by a colleague has
 * no way in from there.
 *
 * Deliberately one scrolling page rather than a swipe-through carousel. A carousel
 * hides how much is left, cannot be re-read once passed, and makes the person press
 * Next four times to reach something they could have taken in at a glance. This reads
 * like the rest of the app, which is itself part of the introduction.
 *
 * It asks for nothing until the person presses the button. The permission list is
 * shown first with the reason beside each one, because a prompt that arrives before
 * the explanation has already lost.
 */
public class WelcomeActivity extends Screen {

    private static final int REQ = 71;

    private final List<Runnable> repaint = new ArrayList<>();
    private TextView startButton;

    @Override protected String title() { return "Boresight"; }

    @Override protected void content() {
        LinearLayout what = pane("What this is");
        hint(what, "A radio survey tool. It reads what the modem, the satellite "
                 + "receiver and the Wi-Fi and Bluetooth scanners already know, shows "
                 + "it live, and can record it with position so a site can be walked "
                 + "and the result looked at afterwards.");
        hint(what, "It changes nothing about how the phone uses the network. Every "
                 + "screen is a readout.");

        permissionPane();

        LinearLayout view = pane("How much to show");
        hint(view, "The menu can be narrowed. Nothing is deleted or disabled by this "
                 + "— the search box in the menu always finds every screen — it only "
                 + "decides what is listed without searching.");
        choice(view, "View", null, Prefs.MENU_VIEW, Drawer.VIEW_DAS,
                new int[]{Drawer.VIEW_DAS, Drawer.VIEW_SIMPLE, Drawer.VIEW_ALL},
                new String[]{"DAS", "Simple", "Everything"}, null);
        hint(view, "DAS is site work: the radio, the survey and the tools that go "
                 + "with them. Simple is the handful of screens most days need. "
                 + "Everything includes the screens that are only interesting with a "
                 + "privileged backend attached.");

        LinearLayout out = pane("What leaves this phone");
        hint(out, "Nothing, unless you send it. There is no account, no telemetry and "
                + "no upload. The survey lives in a database on this device until you "
                + "export it or push it to another device yourself.");
        hint(out, "The map fetches tiles from OpenStreetMap, which means those "
                + "requests show your rough viewing area to that server. The map "
                + "works without them; turn the tile layer off and it draws your "
                + "track on a blank grid.");

        startButton = action(body, "Start", new Runnable() {
            @Override public void run() { done(); }
        });
        hint(body, "This page does not come back. It is in Settings if you want it "
                 + "again.");
        paintAll();
    }

    /**
     * Each permission as a row: what it is, what breaks without it, and whether it is
     * currently granted. One button asks for everything still outstanding, because
     * asking for them one at a time is a worse experience for no gain — the platform
     * shows its own prompt per permission regardless.
     */
    private void permissionPane() {
        LinearLayout p = pane("What it needs, and why");
        hint(p, "Each of these is asked for because something concrete stops working "
              + "without it. Denying one costs you that, and nothing else.");

        for (final String perm : Perms.wanted(this)) {
            final TextView row = kv(p, Perms.name(perm), "");
            TextView why = th.text(this, Perms.why(perm), 10, skin.inkSecondary);
            why.setPadding(th.dp(2), 0, th.dp(10), th.dp(8));
            p.addView(why);
            repaint.add(new Runnable() {
                @Override public void run() {
                    boolean ok = Perms.has(WelcomeActivity.this, perm);
                    row.setText(ok ? "granted" : "not yet");
                    row.setTextColor(ok ? skin.accent(Skin.CH_RSRP) : skin.inkSecondary);
                }
            });
        }

        final TextView grant = action(p, "Grant these", new Runnable() {
            @Override public void run() {
                if (!Perms.request(WelcomeActivity.this, REQ)) {
                    toast("Everything is already granted");
                }
            }
        });
        repaint.add(new Runnable() {
            @Override public void run() {
                boolean all = Perms.missing(WelcomeActivity.this).isEmpty();
                grant.setText(all ? "All granted" : "Grant these");
                grant.setAlpha(all ? 0.5f : 1f);
            }
        });

        hint(p, "You can skip this and grant them later from the Android settings, "
              + "but the live page will have little to say until phone state and "
              + "location are allowed.");
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        paintAll();
    }

    private void paintAll() {
        for (Runnable r : repaint) r.run();
        if (startButton != null) {
            startButton.setText(Perms.core(this) ? "Start" : "Start anyway");
        }
    }

    /** Mark it seen and go to the live page. */
    private void done() {
        Prefs.put(this, Prefs.ONBOARDED, true);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
}

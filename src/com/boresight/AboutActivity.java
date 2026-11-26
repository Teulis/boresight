package com.boresight;

import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Credits, and what this app is standing on.
 *
 * Kept honest rather than flattering: everything borrowed is named, with what was
 * actually taken from it and under what terms. The distinction that matters here is
 * between code that ships inside the APK, a file format that is merely read, and work
 * that was only a reference — conflating those three is how an attribution page ends
 * up both too long and wrong.
 */
public class AboutActivity extends Screen {

    @Override protected String title() { return "ABOUT"; }

    @Override protected void content() {
        LinearLayout head = pane(null);
        TextView name = th.num(this, "BORESIGHT", 24, skin.accent(Skin.CH_RSRP));
        name.setTypeface(skin.face(false, Typeface.BOLD));
        name.setLetterSpacing(0.2f);
        head.addView(name);
        head.addView(th.text(this, "RF survey and diagnostics", 12, skin.inkSecondary));

        kv(head, "Package", getPackageName());
        // From the generated stamp rather than the package manager: a bug report has
        // to name one exact build, and the commit is what makes it exact. A trailing
        // '+' on the commit means the tree had uncommitted changes when it was built.
        kv(head, "Version", Stamp.VERSION);
        kv(head, "Build", Stamp.COMMIT + "   ·   " + Stamp.BUILT);
        kv(head, "Device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "  ·  Android " + android.os.Build.VERSION.RELEASE
                + "  (API " + android.os.Build.VERSION.SDK_INT + ")");
        kv(head, "Backend", Shell.get(this).backendShort());
        kv(head, "This device id", Sync.deviceId(this));

        // --- code that ships inside the APK ---------------------------------
        LinearLayout bundled = pane("Bundled in this app");
        credit(bundled, "Shizuku API",
                "RikkaW / Shizuku — api, aidl, shared and provider, version 13.1.5",
                "Apache License 2.0",
                "The optional privileged backend. The app runs fully without it; when "
              + "the Shizuku service is running and has granted access, commands are "
              + "raised to the shell uid. github.com/RikkaApps/Shizuku-API");
        credit(bundled, "SGP4 / SDP4",
                "Spacetrack Report No. 3, with the Vallado et al. 2006 revision "
              + "(AIAA 2006-6753)",
                "Public domain algorithm, independently implemented here",
                "Satellite propagation in Sgp4.java. Written from the published "
              + "algorithm rather than ported from a codebase, and validated against "
              + "the paper's own test vectors for satellite 00005: 0.7 mm error at "
              + "epoch, 2.3 cm after six hours.");

        // --- formats and data read, not shipped ------------------------------
        LinearLayout formats = pane("Formats and data read");
        credit(formats, "TMOG",
                "Task Manager TMOG by Dave Plummer",
                "Format read, no code used",
                "The look of this app follows TMOG, and it parses TMOG's own .tmogskin "
              + "JSON directly so existing skins work unchanged. Radio metrics are "
              + "mapped onto TMOG's channel names. No TMOG code is included and none "
              + "was consulted — only the skin files.");
        credit(formats, "CelesTrak",
                "Dr T.S. Kelso, celestrak.org",
                "Public TLE data, fetched on request",
                "Orbital elements for the Starlink direct-to-cell screen, fetched only "
              + "when you ask and cached locally. Direct-to-cell satellites are "
              + "identified by CelesTrak's own [DTC] tag.");
        credit(formats, "WiGLE CSV",
                "wigle.net",
                "Format read and written",
                "The survey exports the CSV header WiGLE's importer expects, for "
              + "people who already use that pipeline. No affiliation, no account "
              + "needed, nothing is uploaded by this app.");
        credit(formats, "OpenStreetMap",
                "© OpenStreetMap contributors",
                "Open Database License (ODbL)",
                "Map tiles, when the tile layer is switched on. Tiles are fetched on "
              + "demand and cached on the device. Respect the tile usage policy of "
              + "whichever server is configured.");
        credit(formats, "GeoJSON",
                "RFC 7946, IETF",
                "Open standard",
                "The export format for anything that reads a map layer.");

        // --- specifications ---------------------------------------------------
        LinearLayout specs = pane("Specifications referenced");
        credit(specs, "3GPP TS 36.213",
                "3rd Generation Partnership Project",
                "Published specification",
                "CQI tables 1 and 2, which map the reported channel quality index to a "
              + "modulation order and code rate on the Modulation screen.");
        credit(specs, "3GPP TS 36.133 / 38.133",
                "3rd Generation Partnership Project",
                "Published specification",
                "Timing advance and measurement definitions. One LTE timing advance "
              + "step is 78.125 m of one-way range, which is what the tower position "
              + "estimate is built on.");
        credit(specs, "RFC 7050",
                "IETF",
                "Open standard",
                "The ipv4only.arpa probe the net tools use to discover a NAT64 prefix, "
              + "which is how 464XLAT is confirmed on an IPv6-only bearer.");
        credit(specs, "IAU 1982 GMST",
                "International Astronomical Union",
                "Published standard",
                "Sidereal time, used to turn the propagator's TEME coordinates into "
              + "positions over the ground.");

        // --- platform ---------------------------------------------------------
        LinearLayout plat = pane("Platform");
        credit(plat, "Android SDK and AOSP",
                "Google and the Android Open Source Project",
                "Apache License 2.0",
                "Everything else here is the platform: TelephonyManager, "
              + "ConnectivityManager, the location and sensor stacks, SQLite, and the "
              + "Storage Access Framework that the sync screen uses. No build system, "
              + "no third-party UI library, and no analytics of any kind.");

        LinearLayout priv = pane("What leaves this phone");
        hint(priv, "Nothing, unless you ask. There is no analytics, no crash reporting "
                 + "and no account. Three things reach the network, each only on a "
                 + "deliberate action: tile fetches when the map tile layer is on, a "
                 + "CelesTrak request when you press Update on the satellite screen, "
                 + "and the ping, traceroute and port-scan tools you type into. Sync "
                 + "writes to the folder you choose and nowhere else.");

        LinearLayout thanks = pane("Thanks");
        hint(thanks, "Dave Plummer, for TMOG and for making the skin format legible "
                 + "enough to read from the outside. RikkaW, for Shizuku, which is the "
                 + "only reason an ordinary app can see any of this. T.S. Kelso, for "
                 + "keeping CelesTrak running for thirty years.");
    }

    /**
     * One credit. Four lines because four things matter and running them together as
     * prose makes it impossible to tell at a glance whether something is shipped code
     * or merely a format that was read.
     */
    private void credit(LinearLayout into, String what, String who,
                        String licence, String why) {
        LinearLayout row = th.column(this);
        row.setPadding(0, th.dp(9), 0, th.dp(9));

        TextView a = th.num(this, what, 13, skin.accent(Skin.CH_RSRP));
        a.setTypeface(skin.face(true, Typeface.BOLD));
        row.addView(a);

        row.addView(th.text(this, who, 11, skin.inkPrimary));

        TextView c = th.text(this, licence, 10, skin.accent(Skin.CH_SAT));
        c.setPadding(0, th.dp(1), 0, th.dp(3));
        row.addView(c);

        row.addView(th.text(this, why, 10, Skin.alpha(skin.inkSecondary, 0.92f)));

        View rule = new View(this);
        rule.setBackgroundColor(skin.inkRule);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair()));
        rlp.setMargins(0, th.dp(9), 0, 0);
        row.addView(rule, rlp);

        into.addView(row, Theme.wrap());
    }
}

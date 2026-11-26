package com.boresight;

import android.content.Context;

import java.util.Locale;

/**
 * The device's CSC — Samsung's carrier software customisation code — and what it means
 * for whether dialer keystrings will do anything.
 *
 * This matters more than it looks. The keystring catalogue is the same on every
 * Samsung, but what happens when you dial one is not: the carrier's CSC decides which
 * hidden menus are built into the firmware at all. Showing a user 305 codes without
 * saying that most of them are inert on their particular build is how a tool wastes an
 * afternoon.
 *
 * The code is read from system properties, which an ordinary app may do. On this S22
 * the sales code lands in `persist.audio.sales_code` and the OMC path
 * (`/prism/etc/carriers/TMB`), because Samsung spreads it across several places and
 * which one is populated varies by model and build — so several are tried.
 *
 * The behaviour notes are the user's field experience, not something this app
 * measured, and the UI says so. They are: unlocked and T-Mobile builds run most codes;
 * Verizon builds have them largely stripped; AT&T runs some, but service mode often
 * answers "RAT selection disabled".
 */
public final class Csc {

    public static final int UNKNOWN = 0, OPEN = 1, PARTIAL = 2, LOCKED_DOWN = 3;

    private Csc() { }

    /** Properties Samsung has used for the sales code, most specific first. */
    private static final String[] PROPS = {
            "ro.csc.sales_code",
            "ro.boot.sales_code",
            "ril.sales_code",
            "persist.audio.sales_code",
            "ro.omc.sales_code",
            "persist.omc.sales_code",
    };

    /** The four-letter sales code, or empty if nothing reports one. */
    public static String code(Context c) {
        StringBuilder cmd = new StringBuilder();
        for (String p : PROPS) cmd.append("getprop ").append(p).append("; ");
        Shell.Result r = Shell.get(c).exec(cmd.toString(), 8);
        if (r.ran && r.out != null) {
            for (String line : r.out.split("\n")) {
                String s = line.trim();
                if (s.length() >= 3 && s.length() <= 5 && s.matches("[A-Za-z0-9]+")) {
                    return s.toUpperCase(Locale.US);
                }
            }
        }
        // Fall back to the OMC carrier directory, which is a path rather than a code.
        Shell.Result o = Shell.get(c).exec("getprop mdc.sys.omc_etcpath", 6);
        String p = o.ran && o.out != null ? o.out.trim() : "";
        int slash = p.lastIndexOf('/');
        if (slash > 0 && slash < p.length() - 1) {
            return p.substring(slash + 1).toUpperCase(Locale.US);
        }
        return "";
    }

    public static String carrier(String code) {
        if (code == null) return "unknown";
        switch (code.toUpperCase(Locale.US)) {
            case "XAA": case "XAS": return "US unlocked (Samsung)";
            case "TMB": case "TMK": return "T-Mobile";
            case "VZW": return "Verizon";
            case "ATT": case "AIO": return "AT&T";
            case "SPR": case "VMU": case "BST": return "Sprint lineage";
            case "USC": return "US Cellular";
            case "TFN": case "TRF": return "TracFone";
            case "CCT": return "Comcast / Xfinity Mobile";
            case "SPT": return "Spectrum Mobile";
            case "CHA": return "Charter";
            case "":    return "unknown";
            default:    return code;
        }
    }

    /** How much of the keystring catalogue is likely to be live on this build. */
    public static int outlook(String code) {
        if (code == null || code.isEmpty()) return UNKNOWN;
        switch (code.toUpperCase(Locale.US)) {
            case "XAA": case "XAS": case "TMB": case "TMK":
                return OPEN;
            case "ATT": case "AIO": case "CCT": case "SPT": case "CHA":
            case "TFN": case "TRF": case "USC":
                return PARTIAL;
            case "VZW":
                return LOCKED_DOWN;
            default:
                return UNKNOWN;
        }
    }

    /** A sentence for the top of the keystrings screen. */
    public static String note(String code) {
        switch (outlook(code)) {
            case OPEN:
                return "Unlocked and T-Mobile builds keep most of the hidden menus, so "
                     + "the majority of the dialable codes here should do something.";
            case PARTIAL:
                return "On this carrier some codes work and some do not. Service mode "
                     + "in particular often opens and then refuses the useful parts — "
                     + "\"RAT selection disabled\" is the usual wording. Mark what you "
                     + "find; that is what the marks are for.";
            case LOCKED_DOWN:
                return "Verizon builds have the hidden menus largely stripped out, so "
                     + "expect most of these to do nothing at all. That is the "
                     + "firmware, not the phone being broken and not this app failing "
                     + "to fire them.";
            default:
                return "No sales code could be read, so there is nothing to say about "
                     + "how much of this catalogue is live. Dial one and see.";
        }
    }

    public static String outlookName(int o) {
        switch (o) {
            case OPEN:        return "most should work";
            case PARTIAL:     return "partly disabled";
            case LOCKED_DOWN: return "largely stripped";
            default:          return "unknown";
        }
    }
}

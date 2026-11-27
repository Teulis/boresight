package com.boresight;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pattern matcher for fixed surveillance gear that advertises itself over Wi-Fi or BLE
 * (ALPR cameras and the like). Flock Safety is the case this was built for.
 *
 * Honest about what it is: a rule engine, not an oracle. Cameras are identified only by
 * what their radios broadcast, so a camera with its radios off or renamed is invisible,
 * and an unrelated device that happens to match a name pattern will be flagged. Every
 * rule carries a confidence and a provenance note, and the whole table is user-editable,
 * because the only signatures worth trusting are the ones you have confirmed yourself.
 *
 * Rule kinds:
 *   ssid   - regex against a Wi-Fi SSID
 *   name   - regex against a BLE advertised name
 *   oui    - MAC prefix, e.g. "a4:da:22" (matched case-insensitively, any separator)
 *   mfg    - BLE manufacturer-specific data company id, as 4 hex digits, e.g. "09c8"
 *
 * The mfg kind exists because a name is the weakest handle there is. A vendor renames a
 * product and every name rule goes blind, which is exactly what happened to the
 * "Penguin-" battery packs when a 2025 firmware dropped the prefix and left a bare
 * 10-digit string. The company id in the advertisement is assigned by the Bluetooth SIG
 * and survives that kind of rename.
 */
public final class Detector {

    public static class Rule {
        public long id;
        public String label, vendor, kind, pattern, medium, source;
        public int confidence;
        public boolean enabled;
        Pattern compiled;
        boolean bad;
    }

    public static class Match {
        public Rule rule;
        public String observedName, observedMac, medium;
        public int rssi = Integer.MAX_VALUE;
    }

    /**
     * Bumped whenever SEED changes. An install that was seeded from an older table keeps
     * its own edits but is topped up with the rules it never had, because the usual
     * failure here is silent: the app looks like it is working and simply never matches.
     */
    private static final int SEED_VERSION = 4;
    private static final String SEED_VERSION_KEY = "detector_seed_version";

    /**
     * Starting point, not a reference list. Name patterns only: an OUI claim I cannot
     * verify would flag innocent hardware, so the MAC-prefix rules ship empty for the
     * user to fill in from their own confirmed sightings.
     */
    private static final String[][] SEED = {
        // label, vendor, kind, pattern, medium, confidence, source
        {"Flock SSID",        "Flock Safety",  "ssid", "(?i)^flock",                  "wifi", "70",
         "Community heuristic - unverified. Matches an AP naming itself Flock, which "
         + "covers both the Flock-XXXXXX provisioning SSID and \"Flock Camera net.\"."},
        {"Flock Safety SSID", "Flock Safety",  "ssid", "(?i)flock.?safety",           "wifi", "80",
         "Community heuristic - unverified."},
        {"Flock abbreviated", "Flock Safety",  "ssid", "(?i)(^|[-_ ])flck([-_ 0-9]|$)", "wifi", "60",
         "Community heuristic - unverified. \"flck\" has no \"flock\" substring, so the "
         + "other Flock rules cannot see it. This was a real gap."},
        {"Pigvision",         "Flock Safety",  "ssid", "(?i)pigvision",               "wifi", "60",
         "Community heuristic - unverified; reported alongside Flock SSIDs."},
        {"Flock BLE name",    "Flock Safety",  "name", "(?i)(flock|flck)",            "ble",  "70",
         "Community heuristic - unverified."},
        {"Penguin battery",   "Flock Safety",  "name", "(?i)^penguin[-_ ]?[0-9]{10}$", "ble", "55",
         "Community heuristic - unverified. Penguin is the external battery pack, and it "
         + "advertises over BLE, not Wi-Fi - this rule was previously filed under wifi "
         + "and so could never fire."},
        {"Penguin (unnamed)", "Flock Safety",  "name", "^[0-9]{10}$",                 "ble",  "30",
         "Community heuristic - unverified. Firmware from 2025 dropped the \"Penguin-\" "
         + "prefix, leaving a bare 10-digit name. Deliberately low confidence: any device "
         + "could use a numeric name. Pair it with the company-id rule before believing it."},
        {"FS external battery", "Flock Safety", "name", "(?i)fs ext",                 "ble",  "55",
         "Community heuristic - unverified."},
        {"Nordic DFU target", "unknown",       "name", "(?i)^dfutarg$",               "ble",  "15",
         "Very low confidence and off by default. DfuTarg is the stock Nordic firmware "
         + "update name used by thousands of unrelated products. Useful only as a hint "
         + "when it appears next to another hit."},
        {"XUNTONG company id","Flock Safety",  "mfg",  "09c8",                        "ble",  "80",
         "Bluetooth SIG company id 0x09C8 (XUNTONG), in Penguin battery advertisements. "
         + "The ESP32Marauder and flock-you projects both use this as their primary "
         + "gate rather than the name, because it survives a rename and still fires on "
         + "a unit advertising no name at all. Rated accordingly."},
        {"Flock Safety OUI",  "Flock Safety",  "oui",  "b4:1e:52",                    "",     "85",
         "B4:1E:52 is registered to Flock Safety in the IEEE MA-L database - the one "
         + "signature here that is a matter of public record rather than a community "
         + "sighting. Medium is left blank so it matches on Wi-Fi and BLE alike."},
        {"Community OUI set", "Flock Safety",  "oui",
         "d0:39:57,24:b2:b9,00:f4:8d,14:5a:fc,80:30:49,e0:0a:f6,82:6b:f2,90:35:ea,"
         + "70:c9:4e,3c:91:80,d8:f3:bc",                                              "",     "45",
         "Prefixes collected by the flock-you and DeFlock communities from field "
         + "sightings. Unverified: an audit against the IEEE registry found none of "
         + "them registered to Flock Safety. Two known-bad entries are deliberately "
         + "absent - 48:27:EA belongs to Samsung, and the Espressif blocks would flag "
         + "every ESP32 ever made. Treat a hit as a lead."},
        {"ShotSpotter / Raven","SoundThinking","oui",  "d4:11:d6",                    "",     "45",
         "Gunshot detection rather than plate reading, but the same installs and the "
         + "same question. Community-reported, unverified."},
        {"ALPR in name",      "generic ALPR",  "ssid", "(?i)(^|[-_ ])(alpr|lpr)([-_ ]|$)", "wifi", "50",
         "Generic: some camera installs name themselves after the function."},
        {"Vigilant / L5",     "Motorola",      "ssid", "(?i)(vigilant|l5q|l5f)",      "wifi", "40",
         "Generic vendor-name heuristic - unverified."},
        {"ELSAG ALPR",        "Leonardo",      "ssid", "(?i)(elsag|mpr9)",            "wifi", "40",
         "Generic vendor-name heuristic - unverified."},
        {"Axon fleet",        "Axon",          "ssid", "(?i)axon.?(fleet|cam)",       "wifi", "40",
         "Generic vendor-name heuristic - unverified."},
        {"Unprovisioned unit","Flock Safety",  "oui",  "00:03:7f",                    "wifi", "35",
         "Factory-default radio MAC seen on units not yet provisioned. 00:03:7f is "
         + "Atheros, so this is broad - low confidence on purpose."},
        {"Your OUI list",     "",              "oui",  "",                            "wifi", "95",
         "Blank on purpose. Put a confirmed MAC prefix here, e.g. a4:da:22. Note that "
         + "newer cameras use locally-administered MACs, which no OUI rule can catch."},

        // --- product and competitor names, from Flock-You-Android -------------
        // github.com/MaxwellDPS/Flock-You-Android, docs/detections. Its primary
        // Flock pattern is the one we already had; these are the ones we did not.
        {"Flock Falcon",      "Flock Safety",  "ssid", "(?i)^falcon[-_ ]?",           "wifi", "50",
         "Flock's own product name for the fixed ALPR camera. Moderate confidence "
         + "because 'Falcon' is also a perfectly ordinary name for a home router; "
         + "anchored to the start so it does not match one buried in a longer name."},
        {"Flock Sparrow",     "Flock Safety",  "ssid", "(?i)^sparrow[-_ ]?",          "wifi", "50",
         "Flock product name. Same caveat as Falcon."},
        {"Flock Condor",      "Flock Safety",  "ssid", "(?i)^condor[-_ ]?",           "wifi", "50",
         "Flock product name - the PTZ camera. Same caveat as Falcon."},
        {"FS prefix",         "Flock Safety",  "ssid", "(?i)^fs[-_]",                 "wifi", "45",
         "Two letters is thin, so the separator is required: 'fs-' or 'fs_' at the "
         + "start, never a bare 'fs'. Reported by Flock-You-Android."},
        {"Vigilant ALPR",     "Motorola",      "ssid", "(?i)^vigilant[-_ ]?",         "wifi", "60",
         "Not Flock. Vigilant Solutions is Motorola's plate reader, and a survey that "
         + "only knows one vendor will report a street as clear when it is not."},
        {"Genetec ALPR",      "Genetec",       "ssid", "(?i)^genetec[-_ ]?",          "wifi", "60",
         "Another vendor, same reasoning as Vigilant."},
        {"AutoVu ALPR",       "Genetec",       "ssid", "(?i)^autovu[-_ ]?",           "wifi", "60",
         "Genetec's plate-reader product line."},
        {"Generic ALPR",      "unknown",       "ssid", "(?i)^alpr[-_ ]?",             "wifi", "55",
         "An access point that names itself after the job. Vendor unknown by "
         + "definition, which is why the vendor column says so."},
        {"Generic LPR cam",   "unknown",       "ssid", "(?i)^lpr[-_ ]?cam",           "wifi", "55",
         "As above. 'lpr' alone is too close to printing (LPR is also a print "
         + "protocol), so the pattern requires 'cam' after it."},
    };

    /** Seeded disabled: too broad to be on by default, kept so it can be switched on. */
    private static boolean seedOff(String label) {
        return "Nordic DFU target".equals(label)
            || "Unprovisioned unit".equals(label)
            || "Penguin (unnamed)".equals(label);
    }

    private final Context ctx;
    private final Store store;
    private List<Rule> cache;

    public Detector(Context c) {
        ctx = c.getApplicationContext();
        store = Store.of(ctx);
        seedIfEmpty();
    }

    /**
     * Seed an empty table, and top up an existing one when SEED has grown.
     *
     * Top-up is keyed on the seed version rather than simply inserting every missing
     * label, so a rule the user deliberately deleted stays deleted. Rules they already
     * have are never overwritten; only labels absent from the table are added.
     */
    private void seedIfEmpty() {
        Cursor c = store.db().rawQuery("SELECT COUNT(*) FROM rules", null);
        int n;
        try { n = c.moveToNext() ? c.getInt(0) : 0; } finally { c.close(); }

        int seen = Prefs.integer(ctx, SEED_VERSION_KEY, 0);
        if (n > 0 && seen >= SEED_VERSION) return;

        java.util.Set<String> have = new java.util.HashSet<>();
        if (n > 0) {
            Cursor q = store.db().query("rules", new String[]{"label"},
                    null, null, null, null, null);
            try { while (q.moveToNext()) have.add(q.getString(0)); } finally { q.close(); }
        }

        for (String[] s : SEED) {
            if (have.contains(s[0])) continue;
            ContentValues v = new ContentValues();
            v.put("label", s[0]); v.put("vendor", s[1]); v.put("kind", s[2]);
            v.put("pattern", s[3]); v.put("medium", s[4]);
            v.put("confidence", Integer.parseInt(s[5]));
            v.put("source", s[6]);
            v.put("enabled", s[3].isEmpty() || seedOff(s[0]) ? 0 : 1);
            store.db().insert("rules", null, v);
        }
        Prefs.put(ctx, SEED_VERSION_KEY, SEED_VERSION);
        invalidate();
    }

    /** How many rules the last top-up added, for the detections screen to mention. */
    public int seedVersion() { return SEED_VERSION; }

    // --------------------------------------------------------------- rule table

    public synchronized List<Rule> rules() {
        if (cache != null) return cache;
        List<Rule> out = new ArrayList<>();
        Cursor c = store.db().query("rules", null, null, null, null, null, "vendor, label");
        try {
            while (c.moveToNext()) {
                Rule r = new Rule();
                r.id = c.getLong(c.getColumnIndexOrThrow("id"));
                r.label = c.getString(c.getColumnIndexOrThrow("label"));
                r.vendor = c.getString(c.getColumnIndexOrThrow("vendor"));
                r.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                r.pattern = c.getString(c.getColumnIndexOrThrow("pattern"));
                r.medium = c.getString(c.getColumnIndexOrThrow("medium"));
                r.confidence = c.getInt(c.getColumnIndexOrThrow("confidence"));
                r.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1;
                r.source = c.getString(c.getColumnIndexOrThrow("source"));
                compile(r);
                out.add(r);
            }
        } finally { c.close(); }
        cache = out;
        return out;
    }

    private static void compile(Rule r) {
        r.bad = false;
        r.compiled = null;
        if (r.pattern == null || r.pattern.trim().isEmpty()) { r.bad = true; return; }
        if ("oui".equals(r.kind)) return;                 // matched as a literal prefix
        if ("mfg".equals(r.kind)) {                       // matched as a literal id
            String id = r.pattern.trim().toLowerCase().replace("0x", "");
            r.bad = !id.matches("[0-9a-f]{1,4}");
            return;
        }
        try { r.compiled = Pattern.compile(r.pattern); }
        catch (PatternSyntaxException e) { r.bad = true; }
    }

    public synchronized void invalidate() { cache = null; }

    public void save(Rule r) {
        ContentValues v = new ContentValues();
        v.put("label", r.label); v.put("vendor", r.vendor); v.put("kind", r.kind);
        v.put("pattern", r.pattern); v.put("medium", r.medium);
        v.put("confidence", r.confidence); v.put("enabled", r.enabled ? 1 : 0);
        v.put("source", r.source);
        if (r.id > 0) store.db().update("rules", v, "id=?", new String[]{String.valueOf(r.id)});
        else r.id = store.db().insert("rules", null, v);
        invalidate();
    }

    public void setEnabled(long id, boolean on) {
        ContentValues v = new ContentValues();
        v.put("enabled", on ? 1 : 0);
        store.db().update("rules", v, "id=?", new String[]{String.valueOf(id)});
        invalidate();
    }

    public void delete(long id) {
        store.db().delete("rules", "id=?", new String[]{String.valueOf(id)});
        invalidate();
    }

    public void resetToSeed() {
        store.db().delete("rules", null, null);
        invalidate();
        seedIfEmpty();
    }

    // --------------------------------------------------------------- matching

    private static String bare(String mac) {
        return mac == null ? "" : mac.toLowerCase().replaceAll("[^0-9a-f]", "");
    }

    /** Test one radio sighting against every enabled rule. Strongest match wins. */
    public Match test(String medium, String name, String mac, int rssi) {
        return test(medium, name, mac, rssi, null);
    }

    /**
     * @param companyIds Bluetooth SIG company ids present in a BLE advertisement, or null.
     *                   Only these let an "mfg" rule fire.
     */
    public Match test(String medium, String name, String mac, int rssi, int[] companyIds) {
        Match best = null;
        for (Rule r : rules()) {
            if (!r.enabled || r.bad) continue;
            if (r.medium != null && !r.medium.isEmpty() && !r.medium.equals(medium)) continue;
            boolean hit = false;
            if ("oui".equals(r.kind)) {
                // A rule may carry several prefixes, comma separated. The published
                // community lists are a set, not a single vendor, and splitting them
                // across a dozen rules makes the table unreadable.
                String seen = bare(mac);
                if (!seen.isEmpty()) {
                    for (String part : r.pattern.split(",")) {
                        String want = bare(part);
                        if (want.length() >= 6 && seen.startsWith(want)) { hit = true; break; }
                    }
                }
            } else if ("mfg".equals(r.kind)) {
                if (companyIds != null) {
                    int want = Integer.parseInt(
                            r.pattern.trim().toLowerCase().replace("0x", ""), 16);
                    for (int id : companyIds) if (id == want) { hit = true; break; }
                }
            } else if (r.compiled != null && name != null && !name.isEmpty()) {
                hit = r.compiled.matcher(name).find();
            }
            if (hit && (best == null || r.confidence > best.rule.confidence)) {
                best = new Match();
                best.rule = r;
                best.observedName = name;
                best.observedMac = mac;
                best.medium = medium;
                best.rssi = rssi;
            }
        }
        return best;
    }

    /**
     * Record a match, unless this MAC is already logged in the same patch of ground.
     * Returns true when a new row was written.
     */
    public boolean record(Match m, double lat, double lon, float acc) {
        String storedMac = Prefs.mac(ctx, m.observedMac);
        if (storedMac != null && store.hitSeenNear(storedMac, lat, lon, 0.0005)) return false;
        Store.Hit h = new Store.Hit();
        h.ts = System.currentTimeMillis();
        h.lat = Prefs.blur(ctx, lat);
        h.lon = Prefs.blur(ctx, lon);
        h.acc = acc;
        h.rule = m.rule.label;
        h.vendor = m.rule.vendor;
        h.name = m.observedName;
        h.mac = storedMac;
        h.medium = m.medium;
        h.rssi = m.rssi;
        h.confidence = m.rule.confidence;
        h.note = m.rule.pattern;
        store.insertHit(h);
        return true;
    }

    public int enabledCount() {
        int n = 0;
        for (Rule r : rules()) if (r.enabled && !r.bad) n++;
        return n;
    }
}

package com.boresight;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Radios that travel with you, and are therefore worthless in a survey.
 *
 * A survey log is a record of what was at a place. A car's own infotainment access point,
 * the tyre pressure sensors, the phone mount, the dash cam and the headset are all in the
 * car, so they are observed at every point on the drive and tell you nothing about any of
 * them. They are not merely clutter: because they are seen everywhere, they are the
 * densest thing in the log, they dominate the Wi-Fi and BLE layers on the map, and they
 * inflate every count that would otherwise mean something.
 *
 * Two different jobs, deliberately kept apart:
 *
 *   The patterns below drop a sighting before it is written. They are conservative, and
 *   match things that are unambiguously vehicle or personal gear.
 *
 *   Those patterns do two jobs that are worth telling apart. Your own car rides with you.
 *   Someone else's car, passed on the road, does not — but it is still a transmitter that
 *   has driven off, so a row saying it was at a place is a fact about a moment rather than
 *   about the place, which is what a survey is for. Both are dropped, for different
 *   reasons. A name list is a poor tool for the second job: it only ever catches the few
 *   brands that put their name in the SSID, and the price of reaching further is eating
 *   real fixed access points. So the patterns stay narrow and the rest stays in the log.
 *
 *   The rolling check in {@link #travelsWithYou} is the general answer: anything seen
 *   across a long stretch of road is in the car with you, whatever it calls itself. That
 *   catches the devices no list will ever name.
 *
 * Detection is never filtered. A camera on a pole does not travel with you, so it cannot
 * be caught by the rolling check, and suppressing a possible hit to tidy up a log is the
 * wrong trade every time.
 */
public final class Noise {

    private Noise() { }

    /**
     * Vehicle and carry-with-you radios, as one case-insensitive pattern each.
     *
     * Anchored where it is safe to do so. "myvw" as a bare substring would also match a
     * guest network called "Jimmyvw", which is unlikely but free to avoid.
     */
    private static final String[] VEHICLE = {
        // Factory infotainment hotspots, by brand app naming.
        "(?i)^my(gmc|chevrolet|chevy|buick|cadillac|vw|volkswagen|hyundai|kia|acura"
            + "|honda|audi|bmw|mini|toyota|lexus|subaru|mazda|nissan|infiniti)\\b",
        "(?i)^(onstar|uconnect|mbux|mercedes me|fordpass|bluelink)\\b",
        // Subaru's telematics is called Starlink, and so is the satellite service. A bare
        // "STARLINK" is overwhelmingly a dish — a fixed access point, and in rural country
        // exactly the kind of thing a survey exists to find. Only the Subaru form goes.
        "(?i)^subaru[-_ ]?starlink\\b",
        // Ford SYNC hotspots carry a number. A network simply called "Sync" does not.
        "(?i)^sync ?[0-9]{2,}\\b",
        // EV brands need more than the brand word. "Tesla", "Lucid" and "Polestar" are
        // ordinary words that turn up in business names, and the separator here used to be
        // optional — which made this a bare prefix match that ate "Lucidity",
        // "Teslacoil Labs" and "Rivianna River Cabins".
        "(?i)^(tesla|rivian|lucid|polestar)[-_ ]?"
            // (?![a-z]) rather than \b: an underscore is a word character, so \b would
            // not fire in "TeslaWiFi_Mercury", which is a real name from the survey.
            // "wifi" is deliberately not in this list. "TeslaWiFi_Mercury" in the survey
            // is a CradlePoint router — 00:30:44, sitting beside an IBR900 and an R920 —
            // which is fixed site infrastructure and the opposite of noise.
            + "(sensor|phone|camp|model|air|key|speaker)(?![a-z])",
        "(?i)^(tesla|rivian|lucid|polestar)[-_ ]?[0-9]{2,}\\b",
        "(?i)^(tesla|rivian|lucid|polestar)[-_][0-9a-f]{3,}$",
        // Generic in-car access points.
        "(?i)(wifi in ?the ?car|incontrol|carplay|android ?auto)",
        "(?i)^(gmc|chevy|buick|cadillac|ford|ram|jeep|dodge)[-_ ][0-9a-f]{4,}$",
        // Tyre pressure sensors and OBD dongles broadcast constantly and move with you.
        "(?i)^(tpms|obdii?|elm ?327|veepeak|bluedriver|carista|vgate)\\b",
        // Personal gear riding along in the cab.
        "(?i)^(garmin|rexing|viofo|blackvue|nextbase|thinkware|cobra|escort|uniden)\\b",
        "(?i)^(jabra|plantronics|poly|airpods|galaxy buds|beats|bose|jbl|soundcore)\\b",
        "(?i)^(tile|airtag|chipolo|samsung smarttag|tracker ?tag)\\b",
        // A Flipper Zero advertises as "Flipper <name>" and rides in the bag of whoever
        // owns it, so it is logged for the whole drive. Anchored and separator-bound so a
        // hotel called "Flippers Inn" is still recorded.
        "(?i)^flipper[-_ ]",
    };

    private static Pattern[] compiled;

    private static synchronized Pattern[] patterns() {
        if (compiled == null) {
            compiled = new Pattern[VEHICLE.length];
            for (int i = 0; i < VEHICLE.length; i++) compiled[i] = Pattern.compile(VEHICLE[i]);
        }
        return compiled;
    }

    /**
     * A line in the user's list that is an address rather than a name.
     *
     * Two to six octets, so a full MAC pins one device and three octets pin a whole OUI.
     */
    private static final Pattern MAC_LINE =
            Pattern.compile("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){1,5}$");

    /** Extra patterns the user has added, one per line, in settings. */
    public static List<Pattern> userPatterns(Context c) {
        List<Pattern> out = new ArrayList<>();
        for (String s : userLines(c)) {
            if (MAC_LINE.matcher(s).matches()) continue;   // matched against the address
            try { out.add(Pattern.compile("(?i)" + s)); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** The address prefixes the user has listed, lower-case. */
    public static List<String> userMacs(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : userLines(c)) {
            if (MAC_LINE.matcher(s).matches()) out.add(s.toLowerCase(Locale.US));
        }
        return out;
    }

    private static List<String> userLines(Context c) {
        List<String> out = new ArrayList<>();
        String raw = Prefs.string(c, Prefs.NOISE_EXTRA, "");
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * True when this name is vehicle or personal gear that should not be logged.
     *
     * @param name an SSID or a BLE advertised name; an empty name is never filtered,
     *             because a nameless device is exactly the kind worth keeping.
     */
    public static boolean isVehicle(Context c, String name) {
        return isVehicle(c, name, null);
    }

    /**
     * The same question, with the hardware address as well.
     *
     * An address is the one part of a sighting nobody typed. "Polestar Consulting" is a
     * consultancy and "TeslaWiFi_Mercury" turned out to be a CradlePoint router, but
     * 00:00:5e:00:53:2f is one radio and nothing else, ever.
     *
     * This does not come with a built-in list of vehicle OUIs, because the survey says
     * there is no such thing to ship. Every "Rivian" address in it belongs to Texas
     * Instruments, the Mercedes and Buick modules are LG Innotek, and the whole GM fleet
     * — every myChevrolet, myGMC, myCadillac and myBuick — uses randomised addresses with
     * no registered prefix at all. An OUI rule would miss the largest group entirely and
     * block a chipmaker to catch the rest. The user's own list can still hold a prefix if
     * they ever want one; the app just does not presume to know any.
     */
    public static boolean isVehicle(Context c, String name, String mac) {
        if (!Prefs.bool(c, Prefs.DROP_VEHICLE, true)) return false;
        return filtered(c, name, mac);
    }

    /**
     * Whether the filter matches this name, regardless of whether the filter is switched on.
     *
     * Separate from {@link #isVehicle} because a sweep of data already recorded is a
     * button the user pressed, and should do what they asked rather than silently nothing
     * because live filtering happens to be off.
     */
    public static boolean filtered(Context c, String name) {
        return filtered(c, name, null);
    }

    public static boolean filtered(Context c, String name, String mac) {
        if (mac != null && !mac.isEmpty()) {
            String m = mac.toLowerCase(Locale.US);
            for (String prefix : userMacs(c)) {
                // Octet-boundary match, so 00:00:5e catches the OUI and a full address
                // catches exactly one radio. Without the colon, "00:00:5" would match.
                if (m.equals(prefix) || m.startsWith(prefix + ":")) return true;
            }
        }
        if (name == null || name.trim().isEmpty()) return false;
        String s = name.trim();
        if (matchesBuiltIn(s)) return true;
        for (Pattern p : userPatterns(c)) if (p.matcher(s).find()) return true;
        return false;
    }

    /** The built-in list alone, with no Context, so it can be tested on a desktop. */
    public static boolean matchesBuiltIn(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String s = name.trim();
        for (Pattern p : patterns()) if (p.matcher(s).find()) return true;
        return false;
    }

    /**
     * Add one exact name to the user's own ignore list.
     *
     * Quoted and anchored rather than taken as a regex: this is called from a list row, so
     * the text is a device name and not a pattern, and a name containing a dot or a plus
     * would otherwise match more than the thing the user pointed at.
     *
     * @return the line that was added, or null when it was already there
     */
    public static String ignore(Context c, String name) {
        return ignore(c, name, null);
    }

    /**
     * Prefers the address when there is one.
     *
     * A name is what somebody typed and an address is what the radio is. Ignoring
     * "Polestar Consulting" by name would also ignore the next consultancy; ignoring it
     * by address ignores that one radio and nothing else for the rest of time.
     */
    public static String ignore(Context c, String name, String mac) {
        String line;
        if (mac != null && MAC_LINE.matcher(mac.trim()).matches()) {
            line = mac.trim().toLowerCase(Locale.US);
        } else if (name != null && !name.trim().isEmpty()) {
            line = "^" + Pattern.quote(name.trim()) + "$";
        } else {
            return null;
        }
        String raw = Prefs.string(c, Prefs.NOISE_EXTRA, "");
        if (raw == null) raw = "";
        for (String existing : raw.split("\\r?\\n")) {
            if (existing.trim().equals(line)) return null;
        }
        Prefs.put(c, Prefs.NOISE_EXTRA,
                raw.trim().isEmpty() ? line : raw.trim() + "\n" + line);
        return line;
    }

    /** Which pattern caught it, for a settings screen that has to be auditable. */
    public static String why(Context c, String name) {
        if (name == null) return "";
        for (int i = 0; i < VEHICLE.length; i++) {
            if (patterns()[i].matcher(name.trim()).find()) return VEHICLE[i];
        }
        for (Pattern p : userPatterns(c)) {
            if (p.matcher(name.trim()).find()) return p.pattern() + "  (yours)";
        }
        return "";
    }

    // ------------------------------------------------------------------ rolling

    /**
     * The general case: a radio seen over a long stretch of road is riding with you.
     *
     * No name list can cover every dash cam and every pair of earbuds, but geometry can.
     * A fixed transmitter is observable over at most a cell's or an access point's range;
     * something logged continuously over kilometres is in the vehicle.
     *
     * @param firstLat first sighting position
     * @param spanM    distance between the first and most recent sighting, metres
     * @param count    how many times it has been seen
     */
    public static boolean travelsWithYou(double spanM, int count) {
        // Both conditions matter. A long span with two sightings is just a sparse log of
        // a strong transmitter; a long span with many sightings is something in the car.
        return count >= 8 && spanM > 3000;
    }

    /** Human description of the rule, for the settings screen. */
    public static String rollingRule() {
        return "Anything logged eight or more times across more than 3 km of road is "
             + "treated as riding with you, whatever it is called.";
    }

    /**
     * The rolling rule applied to a live recording session.
     *
     * {@link #travelsWithYou} described the right rule and had tests, but nothing ever
     * called it — which is why a Flipper Zero riding in the car was logged 304 times
     * across a survey instead of 8. The name list can only ever catch what somebody
     * thought to name; this catches the rest by geometry, and needs no list.
     *
     * One entry per identity heard in the session: the position it was first heard at and
     * how many times it has been heard since. When a radio crosses the threshold it is
     * remembered as a rider and dropped for the rest of the session, which costs one hash
     * lookup per sighting and no database work at all.
     *
     * The sightings before the threshold stay in the log. Eight rows along a few km of
     * road is a trace, not a problem, and they are the evidence for the decision; the
     * alternative is deleting rows on a judgement the user never saw made.
     *
     * Deliberately per-session and in memory. A radio that rode with you yesterday may be
     * a fixed access point in a car park today, and carrying that verdict across sessions
     * would make the log depend on history the user cannot inspect.
     */
    public static final class Riders {

        /** Enough for a long day's drive; past this, new identities are simply not tracked. */
        private static final int CAP = 40_000;

        private final java.util.HashMap<String, float[]> seen = new java.util.HashMap<>();
        private final java.util.HashSet<String> riders = new java.util.HashSet<>();
        private int dropped;

        /** True when this identity has shown itself to be travelling with you. */
        public synchronized boolean ridesWithYou(String ident, double lat, double lon) {
            if (ident == null) return false;
            if (riders.contains(ident)) { dropped++; return true; }

            float[] s = seen.get(ident);
            if (s == null) {
                if (seen.size() >= CAP) return false;
                seen.put(ident, new float[]{(float) lat, (float) lon, 1f});
                return false;
            }
            s[2]++;
            double spanM = Locate.haversineM(s[0], s[1], lat, lon);
            if (travelsWithYou(spanM, (int) s[2])) {
                riders.add(ident);
                seen.remove(ident);   // no longer needs the position, only the verdict
                dropped++;
                return true;
            }
            return false;
        }

        /** How many radios the rule has caught, for the recording screen. */
        public synchronized int count() { return riders.size(); }

        /** How many sightings it has kept out of the log. */
        public synchronized int suppressed() { return dropped; }

        public synchronized void reset() {
            seen.clear();
            riders.clear();
            dropped = 0;
        }
    }
}

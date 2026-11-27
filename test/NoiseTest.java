import com.boresight.Noise;

/**
 * Tests for the survey noise filter.
 *
 * The risk here runs one way. A filter that misses a dash cam leaves some clutter in the
 * log; a filter that is too eager silently deletes real survey data, and nothing in the
 * app will ever show you what it threw away. So the false-positive cases below matter
 * more than the true-positive ones.
 */
public class NoiseTest {

    private static int failures;

    public static void main(String[] args) {
        vehicleRadiosAreDropped();
        realNetworksAreKept();
        namelessDevicesAreKept();
        rollingRuleNeedsBothSpanAndCount();
        ridersAreCaughtByGeometry();

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("NoiseTest ok");
    }

    private static void vehicleRadiosAreDropped() {
        System.out.println("\n-- vehicle and carry-along gear --");
        String[] drop = {
            "myChevrolet", "MyGMC", "myVW", "myvw", "MyHyundai", "myAudi",
            "OnStar WiFi", "Uconnect 8912", "FordPass", "Bluelink",
            "Tesla Model 3", "Rivian-4421",
            "Rivian Sensor 3", "Rivian Phone Key", "Rivian Camp Speaker",
            "SUBARU_STARLINK", "Subaru Starlink", "SYNC 3847",
            "TPMS-A41B", "OBDII", "ELM327", "Veepeak",
            "Garmin DriveCam", "VIOFO A129", "BlackVue 900",
            "Galaxy Buds Pro", "AirPods Pro", "Jabra Elite",
            "Tile", "AirTag",
            "Flipper W0pl1p", "flipper-zero", "Flipper Rogue",
        };
        for (String s : drop) check("drops  " + s, Noise.matchesBuiltIn(s));
    }

    /**
     * Everything a survey exists to record. A single over-broad pattern here would
     * quietly gut the Wi-Fi layer, and the loss would be invisible.
     */
    private static void realNetworksAreKept() {
        System.out.println("\n-- real networks must survive --");
        String[] keep = {
            "IHGWiFi.com", "Wi-Fi Auto Connect",          // measured on the test device
            "xfinitywifi", "ATT7f2k9x", "CenturyLink4821",
            "Starbucks WiFi", "Denver Airport Free WiFi",
            "SITE03-AP-1", "DAS-Headend", "Linksys00421",
            // Near misses: these contain a brand name but are not the vehicle's own AP.
            "Jimmyvw", "Hondarosa Ranch Guest", "Fordham House",
            // A camera must never be filtered, whatever else matches.
            "Flock-4A2B", "Penguin-1234567890", "flck-22",
            // "flipper" is only a rider when it is the Flipper Zero's own name shape.
            "Flippers Bar and Grill", "Flipperton Public Library", "FLIPPERS_GUEST",
            // A bare STARLINK is a satellite dish: a fixed access point, and in rural
            // country one of the more useful things a survey can record.
            "STARLINK", "Starlink", "STARLINK-7A2B",
            // A brand word is a word. These were all dropped until the EV pattern stopped
            // treating its separator as optional.
            "Lucidity", "Lucid Dreams Coffee", "Teslacoil Labs",
            "Polestar Consulting", "Rivianna River Cabins",
            "Sync", "Syncro Systems",
            // 00:30:44 in the survey: a CradlePoint router, not a car.
            "TeslaWiFi_Mercury",
        };
        for (String s : keep) check("keeps  " + s, !Noise.matchesBuiltIn(s));
    }

    /** A nameless BLE device is the interesting kind, not the disposable kind. */
    private static void namelessDevicesAreKept() {
        System.out.println("\n-- nameless --");
        check("empty name is not filtered", !Noise.matchesBuiltIn(""));
        check("blank name is not filtered", !Noise.matchesBuiltIn("   "));
        check("null is safe", !Noise.matchesBuiltIn(null));
    }

    private static void rollingRuleNeedsBothSpanAndCount() {
        System.out.println("\n-- rolling 'travels with you' rule --");
        check("many sightings over 10 km rides with you",
                Noise.travelsWithYou(10_000, 40));
        check("a strong AP seen twice far apart is not filtered",
                !Noise.travelsWithYou(10_000, 2));
        check("many sightings in one car park is a fixed AP",
                !Noise.travelsWithYou(60, 40));
        check("nothing seen is not filtered", !Noise.travelsWithYou(0, 0));
    }

    /**
     * The rolling rule, as the recorder actually uses it.
     *
     * travelsWithYou had tests from the day it was written and nothing ever called it,
     * which is why a Flipper Zero in the car was logged 304 times instead of 8. Testing
     * the predicate was not enough; this drives the tracker the recorder holds.
     */
    private static void ridersAreCaughtByGeometry() {
        System.out.println("\n-- rider tracker --");

        // Something in the car, heard every kilometre down a straight road.
        Noise.Riders r = new Noise.Riders();
        int firstCaught = -1;
        for (int i = 0; i < 12; i++) {
            boolean caught = r.ridesWithYou("ble:aa:bb", 39.7392 + i * 0.009, -104.9903);
            if (caught && firstCaught < 0) firstCaught = i;
        }
        check("a radio heard down 10 km of road is caught", firstCaught > 0);
        check("it is not caught before the rule allows it (was " + firstCaught + ")",
                firstCaught >= 7);
        check("the verdict sticks once made",
                r.ridesWithYou("ble:aa:bb", 39.7392, -104.9903));
        check("it is counted once, not per sighting", r.count() == 1);

        // A fixed access point, heard forty times from the same car park.
        Noise.Riders fixedAp = new Noise.Riders();
        boolean everCaught = false;
        for (int i = 0; i < 40; i++) {
            everCaught |= fixedAp.ridesWithYou("wifi:11:22", 39.7392 + i * 0.000005, -104.9903);
        }
        check("a fixed AP heard 40 times in one place is never caught", !everCaught);
        check("and is not counted", fixedAp.count() == 0);

        // Two radios must not be confused with each other.
        Noise.Riders pair = new Noise.Riders();
        for (int i = 0; i < 12; i++) {
            pair.ridesWithYou("ble:rider", 39.7392 + i * 0.009, -104.9903);
            pair.ridesWithYou("ble:fixed", 39.7392, -104.9903);
        }
        check("the rider is caught", pair.ridesWithYou("ble:rider", 39.75, -104.99));
        check("the one beside it is not", !pair.ridesWithYou("ble:fixed", 39.7392, -104.9903));

        check("a null identity is never a rider", !pair.ridesWithYou(null, 39.7, -104.9));

        pair.reset();
        check("reset clears the verdicts", !pair.ridesWithYou("ble:rider", 39.75, -104.99));
        check("and the counts", pair.count() == 0);

        sharedNamesAreNotRiders();
        rotatingAddressesAreCaughtByName();
    }

    /**
     * The failure this guards against deleted real survey rows.
     *
     * A first attempt counted sightings by name as well as by address, to catch the
     * devices that rotate their address. It also caught every name that many separate
     * fixed radios happen to share: a real drive produced "xfinitywifi" at 578 rows
     * across 110 km, which by name looks exactly like something in the car, and is in
     * fact 578 different hotspots each passed once. Default television names, "Office"
     * and the Seos readers on office doors all did the same.
     */
    private static void sharedNamesAreNotRiders() {
        System.out.println("\n-- a shared name is not a rider --");

        // One name, a different access point every kilometre, each heard once.
        Noise.Riders r = new Noise.Riders();
        boolean everCaught = false;
        for (int i = 0; i < 40; i++) {
            everCaught |= r.ridesWithYou("wifi:ap" + i, "xfinitywifi",
                    39.7392 + i * 0.009, -104.9903);
        }
        check("40 hotspots sharing one name over 40 km are never dropped", !everCaught);
        check("and nothing is counted as a rider", r.count() == 0);

        // The same shape with a short name, which is not used as a key at all.
        Noise.Riders tv = new Noise.Riders();
        boolean caughtTv = false;
        for (int i = 0; i < 20; i++) {
            caughtTv |= tv.ridesWithYou("wifi:tv" + i, "[TV] Samsung 7 Series (50)",
                    39.7392 + i * 0.02, -104.9903);
        }
        check("televisions in twenty houses are not one rider", !caughtTv);
    }

    /**
     * The case the name key exists for: one device, a new address every few km.
     *
     * A phone key in the car showed four addresses across fifty kilometres. Keyed on
     * the address alone every rotation starts a fresh count and it is never caught,
     * however far it travels.
     */
    private static void rotatingAddressesAreCaughtByName() {
        System.out.println("\n-- a rotating address is caught by its name --");

        Noise.Riders r = new Noise.Riders();
        // First address, held long enough to prove it travelled.
        for (int i = 0; i < 12; i++) {
            r.ridesWithYou("ble:first", "Rivian Phone Key", 39.7392 + i * 0.009, -104.9903);
        }
        check("the first address is caught",
                r.ridesWithYou("ble:first", "Rivian Phone Key", 39.85, -104.99));
        // It rotates. The new address has never been seen, but the name has.
        check("and the next address is caught at once, not after eight more",
                r.ridesWithYou("ble:second", "Rivian Phone Key", 39.86, -104.99));
        check("a different radio with another name is unaffected",
                !r.ridesWithYou("wifi:elsewhere", "Some Cafe", 39.86, -104.99));
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}

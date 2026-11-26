import com.boresight.Locate;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the tower solver, written after the map was found drawing confidence
 * ellipses that stretched from Montana to Texas.
 *
 * This is the failure mode that makes a desktop test worth writing: the solver never
 * threw, never returned NaN, and reported 75% confidence. Every number it produced was
 * finite and plausible-looking in isolation. Only the picture was absurd, and only to
 * someone who knows how far a cell tower actually reaches.
 */
public class LocateTest {

    private static int failures;

    public static void main(String[] args) {
        wellConditionedFixIsAccepted();
        reusedPciInTwoCitiesIsSplit();
        mergingReusedPciProducesNonsense();
        collinearSightingsAreRejected();
        qualityFallsWithEllipseSize();
        singleSightingIsNotAFix();

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("LocateTest ok");
    }

    // Roughly Longmont, Colorado: the user's patch, and a latitude where the
    // cos(latitude) term in the east-west scaling actually matters.
    private static final double LAT0 = 40.167, LON0 = -105.101;

    private static final double M_PER_DEG_LAT = 111132.0;
    private static double mPerDegLon(double lat) {
        return 111320.0 * Math.cos(Math.toRadians(lat));
    }

    /**
     * Noise matters here, and leaving it out is how the first version of this test
     * passed a case it should have failed.
     *
     * The covariance the solver reports is scaled by the residual variance, so
     * measurements that agree perfectly produce a tiny ellipse no matter how bad the
     * geometry is. Exact synthetic TAs therefore made a collinear highway drive look
     * like an excellent fix. Real timing advance is quantised to 78 m steps and reads
     * long in multipath, and it is that disagreement, amplified by degenerate geometry,
     * that blows the cross-range axis out to hundreds of kilometres.
     *
     * Seeded so the suite is reproducible.
     */
    private static java.util.Random noise = new java.util.Random(20261125L);

    private static Locate.Point sighting(double towerLat, double towerLon,
                                         double bearingDeg, double metres) {
        return sighting(towerLat, towerLon, bearingDeg, metres, 0);
    }

    private static Locate.Point sighting(double towerLat, double towerLon,
                                         double bearingDeg, double metres,
                                         double multipathM) {
        double b = Math.toRadians(bearingDeg);
        double north = metres * Math.cos(b), east = metres * Math.sin(b);
        Locate.Point p = new Locate.Point();
        p.lat = towerLat + north / M_PER_DEG_LAT;
        p.lon = towerLon + east / mPerDegLon(towerLat);
        // TA always reads path length, never straight line, so the error is one-sided.
        double measured = metres + multipathM * noise.nextDouble();
        p.ta = (int) Math.round(measured / Locate.TA_METRES);
        p.dbm = -85;
        p.freqMhz = 2137.5;
        p.acc = 5f;
        return p;
    }

    /** Sightings all round the tower: the geometry a solver is entitled to succeed on. */
    private static void wellConditionedFixIsAccepted() {
        List<Locate.Point> pts = new ArrayList<>();
        // Same 150 m of multipath error as the collinear case below, so the only thing
        // differing between the two tests is the geometry.
        for (double b = 0; b < 360; b += 45) pts.add(sighting(LAT0, LON0, b, 1200, 150));

        Locate.Fix f = Locate.solve(pts, 60, 3.0f, true);

        check("good geometry solves", f.ok());
        check("good geometry is plausible", f.plausible);

        double err = metres(f.lat, f.lon, LAT0, LON0);
        check("fix lands within 250 m of truth (got " + Math.round(err) + " m)", err < 250);
        check("ellipse stays within the useful bound (got "
                + Math.round(f.majorM) + " m)", f.majorM <= Locate.MAX_USEFUL_ELLIPSE_M);
        check("quality is high (got " + f.quality + ")", f.quality >= 55);
    }

    /**
     * The bug that actually put 500 km ellipses on the map.
     *
     * There are only 504 physical cell ids, so when the network withholds the full cell
     * id and the app falls back to PCI + EARFCN, the same label turns up again in the
     * next city. Clustering has to separate them before anything is solved.
     */
    private static void reusedPciInTwoCitiesIsSplit() {
        List<Locate.Point> both = new ArrayList<>();
        for (double b = 0; b < 360; b += 60) both.add(sighting(LAT0, LON0, b, 1000, 120));
        // Same PCI, same channel, 90 km north — Fort Collins rather than Longmont.
        double farLat = LAT0 + 90_000 / M_PER_DEG_LAT;
        for (double b = 0; b < 360; b += 60) both.add(sighting(farLat, LON0, b, 1000, 120));

        List<List<Locate.Point>> groups = Locate.cluster(both, Locate.CLUSTER_GAP_M);
        check("two cities split into two groups (got " + groups.size() + ")",
                groups.size() == 2);

        for (List<Locate.Point> g : groups) {
            Locate.Fix f = Locate.solve(g, 60, 3.0f, true);
            check("each group solves plausibly", f.ok() && f.plausible);
            check("each group's ellipse is sane (got "
                    + Math.round(f.majorM) + " m)",
                    f.majorM <= Locate.MAX_USEFUL_ELLIPSE_M);
        }

        // And a single cell's own coverage must never be torn apart by clustering.
        List<Locate.Point> one = new ArrayList<>();
        for (double b = 0; b < 360; b += 30) one.add(sighting(LAT0, LON0, b, 2500, 120));
        check("one cell stays in one group",
                Locate.cluster(one, Locate.CLUSTER_GAP_M).size() == 1);
    }

    /** Proof the gate catches it even if clustering is ever bypassed. */
    private static void mergingReusedPciProducesNonsense() {
        List<Locate.Point> both = new ArrayList<>();
        for (double b = 0; b < 360; b += 60) both.add(sighting(LAT0, LON0, b, 1000, 120));
        double farLat = LAT0 + 90_000 / M_PER_DEG_LAT;
        for (double b = 0; b < 360; b += 60) both.add(sighting(farLat, LON0, b, 1000, 120));

        Locate.Fix merged = Locate.solve(both, 60, 3.0f, true);
        check("a merged two-city fit is refused"
                + " (major " + Math.round(merged.majorM) + " m, plausible "
                + merged.plausible + ", quality " + merged.quality + ")",
                !merged.ok() || merged.quality < 25);
    }

    /**
     * The other real-world case: a highway drive. Every sighting lies on one line, so range is
     * well constrained and cross-range is not constrained at all. The solver still
     * converges, and the cross-range covariance explodes.
     */
    private static void collinearSightingsAreRejected() {
        // A short stretch of road a long way from the tower: twenty sightings over
        // 600 m of travel, 15 km out. Range is pinned, cross-range is guesswork, and
        // the baseline is far too short to triangulate anything.
        List<Locate.Point> pts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double along = -300 + i * 30;
            double east = 15000;
            Locate.Point p = new Locate.Point();
            p.lat = LAT0 + along / M_PER_DEG_LAT;
            p.lon = LON0 + east / mPerDegLon(LAT0);
            double d = Math.hypot(along, east);
            p.ta = (int) Math.round((d + 150 * noise.nextDouble()) / Locate.TA_METRES);
            p.dbm = -90;
            p.freqMhz = 2137.5;
            p.acc = 8f;
            pts.add(p);
        }

        Locate.Fix f = Locate.solve(pts, 60, 3.0f, true);

        // A ring of sightings at comparable range, for comparison. The only thing that
        // differs is the geometry, so the gap between the two is what the covariance is
        // supposed to express.
        List<Locate.Point> ring = new ArrayList<>();
        for (double b = 0; b < 360; b += 45) ring.add(sighting(LAT0, LON0, b, 15000, 150));
        Locate.Fix good = Locate.solve(ring, 60, 3.0f, true);

        // Now that the covariance is dimensionally correct, this geometry does produce
        // a usable-ish answer, and saying otherwise would be the dishonest result. What
        // must hold is that it is visibly worse, and that it says why.
        check("collinear is far less certain than a ring at the same range"
                + " (" + Math.round(f.majorM) + " m vs "
                + Math.round(good.majorM) + " m)",
                f.majorM > good.majorM * 5);
        check("collinear scores lower (" + f.quality + " vs " + good.quality + ")",
                f.quality < good.quality - 20);
        check("the caveat warns about the geometry",
                f.caveat.contains("arc") || !f.implausible.isEmpty());
    }

    /** Nothing the solver returns may claim a cell reaches further than one can. */
    private static void qualityFallsWithEllipseSize() {
        List<Locate.Point> tight = new ArrayList<>();
        for (double b = 0; b < 360; b += 60) tight.add(sighting(LAT0, LON0, b, 900));
        Locate.Fix good = Locate.solve(tight, 60, 3.0f, true);

        List<Locate.Point> smeared = new ArrayList<>();
        for (double b = 0; b < 40; b += 8) smeared.add(sighting(LAT0, LON0, b, 9000));
        Locate.Fix vague = Locate.solve(smeared, 60, 3.0f, true);

        check("a tight fix outscores a smeared one ("
                + good.quality + " vs " + vague.quality + ")",
                good.quality > vague.quality);

        if (good.ok()) {
            check("accepted fixes stay inside the physical bound",
                    good.majorM <= Locate.MAX_USEFUL_ELLIPSE_M);
        }
    }

    /** One reading is a radius, not a position, and must never be drawn as one. */
    private static void singleSightingIsNotAFix() {
        List<Locate.Point> pts = new ArrayList<>();
        pts.add(sighting(LAT0, LON0, 90, 1500));
        Locate.Fix f = Locate.solve(pts, 60, 3.0f, true);
        check("a single sighting is not ok()", !f.ok());
    }

    // ------------------------------------------------------------------ helpers

    private static double metres(double la1, double lo1, double la2, double lo2) {
        double dn = (la2 - la1) * M_PER_DEG_LAT;
        double de = (lo2 - lo1) * mPerDegLon(la1);
        return Math.hypot(dn, de);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}

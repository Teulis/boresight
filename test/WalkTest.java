import com.boresight.Walk;

import java.util.ArrayList;
import java.util.List;

/**
 * The indoor walk test's interpolation.
 *
 * This is the piece that fails invisibly: readings get placed *somewhere*, the heatmap
 * looks convincing, and nobody can tell from the picture that the positions are wrong.
 * So it is checked against the cases that actually arise on a walk — including the
 * degenerate ones a surveyor will hit by accident.
 */
public class WalkTest {

    static int failures = 0;

    public static void main(String[] args) {
        straightLeg();
        clamping();
        sameInstant();
        singleSample();
        coverage();
        undo();
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.printf("%-54s %s%s%n", what, ok ? "pass" : "FAIL",
                ok ? "" : "   <- " + detail);
        if (!ok) failures++;
    }

    static Walk.Sample at(long ts, int dbm) {
        Walk.Sample s = new Walk.Sample();
        s.ts = ts; s.dbm = dbm; s.tech = "LTE";
        return s;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }

    /** The ordinary case: tap, walk, tap. */
    static void straightLeg() {
        System.out.println("-- a straight leg --");
        List<Walk.Sample> p = new ArrayList<>();
        p.add(at(1000, -80));   // at the start
        p.add(at(1500, -85));   // halfway
        p.add(at(2000, -90));   // at the end
        List<Walk.Sample> out = Walk.spread(p,
                new Walk.Point(0, 0, 1000), new Walk.Point(100, 50, 2000));

        check("all three placed", out.size() == 3, "got " + out.size());
        check("first sits on the start point",
                near(out.get(0).x, 0) && near(out.get(0).y, 0),
                out.get(0).x + "," + out.get(0).y);
        check("middle sits halfway",
                near(out.get(1).x, 50) && near(out.get(1).y, 25),
                out.get(1).x + "," + out.get(1).y);
        check("last sits on the end point",
                near(out.get(2).x, 100) && near(out.get(2).y, 50),
                out.get(2).x + "," + out.get(2).y);
    }

    /** A reading timestamped outside the leg must not land off the end of it. */
    static void clamping() {
        System.out.println("\n-- readings outside the leg --");
        List<Walk.Sample> p = new ArrayList<>();
        p.add(at(500, -80));    // before the first tap
        p.add(at(5000, -80));   // after the second
        List<Walk.Sample> out = Walk.spread(p,
                new Walk.Point(10, 10, 1000), new Walk.Point(20, 10, 2000));
        check("early reading clamps to the start", near(out.get(0).x, 10),
                String.valueOf(out.get(0).x));
        check("late reading clamps to the end", near(out.get(1).x, 20),
                String.valueOf(out.get(1).x));
        check("nothing lands outside the segment",
                out.get(0).x >= 10 && out.get(1).x <= 20, "");
    }

    /** Two taps in the same millisecond — a double tap, or a very fast surveyor. */
    static void sameInstant() {
        System.out.println("\n-- zero-length leg --");
        List<Walk.Sample> p = new ArrayList<>();
        p.add(at(1000, -80));
        p.add(at(1000, -82));
        List<Walk.Sample> out = Walk.spread(p,
                new Walk.Point(5, 5, 1000), new Walk.Point(9, 9, 1000));
        check("no divide by zero, all placed", out.size() == 2, "");
        check("everything lands on the end point",
                near(out.get(0).x, 9) && near(out.get(1).x, 9),
                out.get(0).x + " / " + out.get(1).x);
        boolean finite = !Float.isNaN(out.get(0).x) && !Float.isInfinite(out.get(0).x);
        check("coordinates are finite", finite, "got " + out.get(0).x);
    }

    static void singleSample() {
        System.out.println("\n-- one reading on a leg --");
        List<Walk.Sample> p = new ArrayList<>();
        p.add(at(1750, -80));
        List<Walk.Sample> out = Walk.spread(p,
                new Walk.Point(0, 0, 1000), new Walk.Point(0, 100, 2000));
        check("placed three quarters along", near(out.get(0).y, 75),
                String.valueOf(out.get(0).y));
        check("empty input yields nothing",
                Walk.spread(new ArrayList<Walk.Sample>(),
                        new Walk.Point(0, 0, 0), new Walk.Point(1, 1, 1)).isEmpty(), "");
        check("null input is safe",
                Walk.spread(null, new Walk.Point(0, 0, 0), new Walk.Point(1, 1, 1)).isEmpty(), "");
    }

    /** The acceptance number: percent of readings at or above a threshold. */
    static void coverage() {
        System.out.println("\n-- coverage against the criterion --");
        Walk w = new Walk();
        w.tap(0, 0, 1000);
        for (int i = 0; i < 100; i++) w.collect(at(1000 + i, i < 96 ? -90 : -110));
        w.tap(100, 0, 1100);

        check("all readings placed", w.placedCount() == 100,
                "got " + w.placedCount());
        double c = w.coverage(Walk.F_DBM, -95);
        check("96 of 100 above -95 dBm reads as 96%", Math.abs(c - 96.0) < 0.001,
                "got " + c);
        check("passes a 95% criterion", c >= 95, "got " + c);

        double strict = w.coverage(Walk.F_DBM, -85);
        check("tighter threshold fails", strict < 95, "got " + strict);

        Walk empty = new Walk();
        check("no samples gives NaN, not zero",
                Double.isNaN(empty.coverage(Walk.F_DBM, -95)), "");
    }

    /** A misplaced tap has to be undoable, or the walk is ruined. */
    static void undo() {
        System.out.println("\n-- undo a mistaken tap --");
        Walk w = new Walk();
        w.tap(0, 0, 1000);
        w.collect(at(1050, -80));
        w.tap(10, 0, 1100);
        w.collect(at(1150, -85));
        w.tap(999, 999, 1200);          // tapped the wrong place

        int before = w.samples.size();
        w.undoLastPoint();
        check("waypoint removed", w.points.size() == 2, "got " + w.points.size());
        check("its leg's samples removed", w.samples.size() < before,
                before + " -> " + w.samples.size());
        check("earlier leg survives", w.samples.size() == 1,
                "got " + w.samples.size());

        w.undoLastPoint();
        w.undoLastPoint();
        check("undoing past the start is safe", w.points.isEmpty(), "");
        check("samples cleared with the last point", w.samples.isEmpty(), "");
    }
}

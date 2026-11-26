import com.boresight.Squarify;
import java.util.Random;

/**
 * Checks the treemap layout against the properties that make it meaningful rather than
 * merely drawable: areas in proportion to sizes, no overlaps, nothing outside bounds,
 * and aspect ratios actually near square.
 */
public class SquarifyTest {

    static int failures = 0;

    public static void main(String[] args) {
        fixed();
        random();
        degenerate();
        aspect();
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.printf("%-46s %s%s%n", what, ok ? "pass" : "FAIL",
                ok ? "" : "   <- " + detail);
        if (!ok) failures++;
    }

    static void fixed() {
        System.out.println("-- fixed set, 6 items in 1000x600 --");
        long[] sizes = { 600, 300, 100, 60, 30, 10 };
        float[][] r = Squarify.layout(sizes, 0, 0, 1000, 600);
        verify("fixed", sizes, r, 0, 0, 1000, 600);
    }

    static void random() {
        System.out.println("\n-- 200 random sets --");
        Random rnd = new Random(20261125L);
        int bad = 0;
        for (int t = 0; t < 200; t++) {
            int n = 1 + rnd.nextInt(60);
            long[] sizes = new long[n];
            for (int i = 0; i < n; i++) sizes[i] = 1 + rnd.nextInt(1_000_000);
            float w = 50 + rnd.nextInt(1500), h = 50 + rnd.nextInt(1500);
            float[][] r = Squarify.layout(sizes, 0, 0, w, h);
            if (!quiet(sizes, r, 0, 0, w, h)) bad++;
        }
        check("200 random layouts hold all invariants", bad == 0, bad + " failed");
    }

    static void degenerate() {
        System.out.println("\n-- degenerate input --");
        check("empty input returns empty",
                Squarify.layout(new long[0], 0, 0, 100, 100).length == 0, "");
        float[][] z = Squarify.layout(new long[]{ 0, 0 }, 0, 0, 100, 100);
        check("all-zero sizes produce no area",
                z[0][2] * z[0][3] == 0 && z[1][2] * z[1][3] == 0, "");
        float[][] one = Squarify.layout(new long[]{ 5 }, 0, 0, 200, 100);
        check("single item fills the whole rect",
                Math.abs(one[0][2] * one[0][3] - 20000) < 1,
                "got " + (one[0][2] * one[0][3]));
        float[][] neg = Squarify.layout(new long[]{ -5, 10 }, 0, 0, 100, 100);
        check("negative size does not blow up", neg.length == 2, "");
        float[][] zeroRect = Squarify.layout(new long[]{ 1, 2 }, 0, 0, 0, 100);
        check("zero-width rect returns zero areas",
                zeroRect[0][2] * zeroRect[0][3] == 0, "");
    }

    static void aspect() {
        System.out.println("\n-- squareness --");
        long[] sizes = new long[40];
        for (int i = 0; i < sizes.length; i++) sizes[i] = 40 - i;
        float[][] r = Squarify.layout(sizes, 0, 0, 800, 800);
        double worst = 0;
        for (float[] x : r) {
            if (x[2] <= 0 || x[3] <= 0) continue;
            worst = Math.max(worst, Math.max(x[2] / x[3], x[3] / x[2]));
        }
        // Slice-and-dice on this input gives ratios in the hundreds. Squarified should
        // stay in single digits.
        check("worst aspect ratio under 10", worst < 10,
                String.format("worst = %.1f", worst));
        System.out.printf("   worst aspect ratio: %.2f%n", worst);
    }

    static void verify(String tag, long[] sizes, float[][] r,
                       float bx, float by, float bw, float bh) {
        long total = 0;
        for (long s : sizes) total += Math.max(0, s);
        double areaPer = (double) bw * bh / total;

        double covered = 0;
        boolean areasOk = true, boundsOk = true;
        for (int i = 0; i < sizes.length; i++) {
            double want = sizes[i] * areaPer;
            double got = (double) r[i][2] * r[i][3];
            covered += got;
            if (Math.abs(got - want) > Math.max(1.0, want * 0.02)) {
                areasOk = false;
                System.out.printf("   item %d wanted %.0f got %.0f%n", i, want, got);
            }
            if (r[i][0] < bx - 0.5 || r[i][1] < by - 0.5
                    || r[i][0] + r[i][2] > bx + bw + 0.5
                    || r[i][1] + r[i][3] > by + bh + 0.5) boundsOk = false;
        }
        check(tag + ": area proportional to size", areasOk, "");
        check(tag + ": all rects inside bounds", boundsOk, "");
        check(tag + ": covers the whole rect",
                Math.abs(covered - bw * bh) < bw * bh * 0.02,
                String.format("covered %.0f of %.0f", covered, bw * bh));
        check(tag + ": no overlaps", noOverlap(r), "");
    }

    static boolean quiet(long[] sizes, float[][] r, float bx, float by, float bw, float bh) {
        long total = 0;
        for (long s : sizes) total += Math.max(0, s);
        if (total <= 0) return true;
        double areaPer = (double) bw * bh / total;
        double covered = 0;
        for (int i = 0; i < sizes.length; i++) {
            double want = sizes[i] * areaPer;
            double got = (double) r[i][2] * r[i][3];
            covered += got;
            if (Math.abs(got - want) > Math.max(1.0, want * 0.02)) return false;
            if (r[i][0] < bx - 0.5 || r[i][1] < by - 0.5
                    || r[i][0] + r[i][2] > bx + bw + 0.5
                    || r[i][1] + r[i][3] > by + bh + 0.5) return false;
        }
        if (Math.abs(covered - bw * bh) > bw * bh * 0.02) return false;
        return noOverlap(r);
    }

    /** Pairwise, with a small tolerance for shared edges. */
    static boolean noOverlap(float[][] r) {
        for (int i = 0; i < r.length; i++) {
            if (r[i][2] <= 0 || r[i][3] <= 0) continue;
            for (int j = i + 1; j < r.length; j++) {
                if (r[j][2] <= 0 || r[j][3] <= 0) continue;
                float ox = Math.min(r[i][0] + r[i][2], r[j][0] + r[j][2])
                         - Math.max(r[i][0], r[j][0]);
                float oy = Math.min(r[i][1] + r[i][3], r[j][1] + r[j][3])
                         - Math.max(r[i][1], r[j][1]);
                if (ox > 0.5f && oy > 0.5f) {
                    System.out.printf("   overlap between %d and %d (%.1f x %.1f)%n",
                            i, j, ox, oy);
                    return false;
                }
            }
        }
        return true;
    }
}

import com.boresight.Rate;

/**
 * Tests for the windowed throughput figure.
 *
 * The behaviour that matters is the one that prompted it: a counter that updates in
 * bursts must not read as alternating full speed and zero. But the fix must not become
 * a lie in the other direction either — a transfer that really stops has to reach zero,
 * and a steady transfer has to report its actual rate, not a flattened one.
 */
public class RateTest {

    private static int failures;

    public static void main(String[] args) {
        steadyIsExact();
        burstyCounterStopsFlickering();
        aStoppedTransferReachesZero();
        counterResetDoesNotSpike();
        firstSampleHasNoAnswer();

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("RateTest ok");
    }

    /** 10 KB every 100 ms is 100 KB/s, and must be reported as exactly that. */
    private static void steadyIsExact() {
        System.out.println("\n-- steady --");
        Rate r = new Rate(3000, 64);
        long bytes = 0, t = 0;
        float last = Float.NaN;
        for (int i = 0; i < 60; i++) {
            bytes += 10_240;
            t += 100;
            last = r.perSecond(bytes, t);
        }
        check("reports 102400 B/s (got " + Math.round(last) + ")",
                Math.abs(last - 102_400f) < 1f);
    }

    /**
     * The real failure. The counter only moves every fourth tick, so tick-to-tick
     * subtraction gives 0, 0, 0, 4x, 0, 0, 0, 4x.
     */
    private static void burstyCounterStopsFlickering() {
        System.out.println("\n-- bursty counter --");
        Rate r = new Rate(3000, 64);
        long bytes = 0, t = 0;
        float min = Float.MAX_VALUE, max = 0;
        for (int i = 0; i < 40; i++) {
            if (i % 4 == 3) bytes += 40_960;        // four ticks' worth, all at once
            t += 250;
            float v = r.perSecond(bytes, t);
            if (Float.isNaN(v)) continue;
            if (i > 12) { min = Math.min(min, v); max = Math.max(max, v); }
        }
        // 40,960 bytes arriving once per second is 40,960 B/s, however it is chunked.
        check("never reads zero mid-transfer (low was " + Math.round(min) + ")", min > 0);
        check("stays near the true 40960 B/s (" + Math.round(min) + " to "
                + Math.round(max) + ")",
                min > 36_000 && max < 46_000);

        // What the old arithmetic would have produced, for contrast.
        long prev = 0, pt = 0; bytes = 0; t = 0;
        boolean sawZero = false, sawFull = false;
        for (int i = 0; i < 40; i++) {
            if (i % 4 == 3) bytes += 40_960;
            t += 250;
            float v = (bytes - prev) * 1000f / (t - pt);
            prev = bytes; pt = t;
            if (i > 12) { if (v == 0) sawZero = true; if (v > 150_000) sawFull = true; }
        }
        check("tick-to-tick really does alternate 0 and full", sawZero && sawFull);
    }

    private static void aStoppedTransferReachesZero() {
        System.out.println("\n-- a real stop --");
        Rate r = new Rate(1000, 32);
        long bytes = 0, t = 0;
        for (int i = 0; i < 20; i++) { bytes += 10_000; t += 100; r.perSecond(bytes, t); }
        float v = 0;
        for (int i = 0; i < 20; i++) { t += 100; v = r.perSecond(bytes, t); }
        check("falls to zero once nothing moves (got " + v + ")", v == 0f);
    }

    private static void counterResetDoesNotSpike() {
        System.out.println("\n-- counter reset --");
        Rate r = new Rate(3000, 32);
        long t = 0;
        for (int i = 1; i <= 10; i++) r.perSecond(i * 100_000L, t += 100);
        float after = r.perSecond(500, t += 100);          // counter restarted
        check("no negative rate (got " + after + ")", Float.isNaN(after) || after >= 0);
        float next = r.perSecond(1500, t += 100);
        check("recovers sensibly afterwards (got " + next + ")",
                Float.isNaN(next) || (next >= 0 && next < 100_000));
    }

    private static void firstSampleHasNoAnswer() {
        System.out.println("\n-- first sample --");
        Rate r = new Rate();
        check("one reading is not a rate", Float.isNaN(r.perSecond(1000, 0)));
        check("not ready yet", !r.ready());
        r.perSecond(2000, 1000);
        check("ready after two", r.ready());
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}

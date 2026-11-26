package com.boresight;

/**
 * A throughput figure that does not flicker.
 *
 * The obvious way to turn a cumulative byte counter into a rate is to subtract the last
 * reading from this one and divide by the tick. It produces a graph that alternates
 * 15 Mb/s, 0, 14 Mb/s, 0 — not because the transfer stalls but because the counter does
 * not update on the same beat as the sampler. One tick catches two updates' worth of
 * bytes and the next catches none, and the average of the two is right while neither
 * reading is.
 *
 * This measures across a window instead: the bytes between the oldest reading still
 * inside the window and the newest, over the real time between those two readings. No
 * bytes are invented or discarded, so the figure is a true average rather than a
 * smoothed one — a transfer that genuinely stops still falls to zero, it just takes the
 * window to get there.
 */
public final class Rate {

    private final long windowMs;
    private final long[] when;
    private final long[] count;
    private int n;          // how many slots are filled
    private int head;       // next slot to write

    public Rate(long windowMs, int samples) {
        this.windowMs = windowMs;
        int cap = Math.max(2, samples);
        when = new long[cap];
        count = new long[cap];
    }

    /** Default: three seconds of history, enough for a one-second tick to settle. */
    public Rate() { this(3000, 16); }

    /**
     * Feed a cumulative counter and get the rate per second.
     *
     * @return units per second, or NaN until there is enough history to divide by
     */
    public float perSecond(long cumulative, long nowMs) {
        // A counter that went backwards is a different counter: the interface went
        // down, the process restarted, or the kernel wrapped it. Averaging across that
        // would produce one enormous negative spike and then a wrong average.
        if (n > 0 && cumulative < newestCount()) reset();

        when[head] = nowMs;
        count[head] = cumulative;
        head = (head + 1) % when.length;
        if (n < when.length) n++;

        int oldest = oldestInWindow(nowMs);
        if (oldest < 0) return Float.NaN;

        long dt = nowMs - when[oldest];
        if (dt <= 0) return Float.NaN;
        return (cumulative - count[oldest]) * 1000f / dt;
    }

    /** Throw away the history, for a counter that is no longer comparable. */
    public void reset() { n = 0; head = 0; }

    public boolean ready() { return n >= 2; }

    private long newestCount() {
        int last = (head - 1 + when.length) % when.length;
        return count[last];
    }

    /**
     * The oldest reading still inside the window, or the oldest we have when the whole
     * history is younger than the window. Returns -1 while there is only one reading.
     */
    private int oldestInWindow(long nowMs) {
        if (n < 2) return -1;
        int best = -1;
        for (int i = 0; i < n; i++) {
            int idx = (head - 1 - i + 2 * when.length) % when.length;
            if (idx == (head - 1 + when.length) % when.length) continue;   // the one just written
            if (nowMs - when[idx] <= windowMs) best = idx;
            else { if (best < 0) best = idx; break; }
        }
        return best;
    }
}

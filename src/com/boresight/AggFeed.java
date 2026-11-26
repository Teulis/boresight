package com.boresight;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The shell half of carrier aggregation: reads the physical channel configuration
 * through the privileged backend and hands it to {@link Agg}.
 *
 * It is a separate class so that Agg itself touches nothing but the telephony API and
 * string parsing, and can therefore be compiled and tested on a desktop. Shell pulls in
 * the Shizuku library, and a parser that fails quietly is exactly the kind of code that
 * needs a test — a parse that returns nothing looks identical to a phone that is not
 * aggregating, and the screen would then claim a single carrier with total confidence.
 *
 * Why this is worth doing at all: the component carriers of a secondary cell — band,
 * PCI, bandwidth — are in the physical channel configuration, and Android reserves that
 * to READ_PRECISE_PHONE_STATE, which is signature|privileged and cannot be granted to
 * an ordinary app. The shell holds it. Shizuku runs as the shell. So the same facts
 * that service mode shows are readable here, through a command rather than an API.
 */
public final class AggFeed {

    private static ExecutorService pool;

    private AggFeed() { }

    /**
     * Start a read if one is due and a privileged backend is attached. Returns at once;
     * the answer lands in Agg when it arrives. Cheap to call on every refresh tick.
     */
    public static void refresh(Context c) {
        if (!Agg.privilegedStale()) return;
        final Shell sh = Shell.get(c);
        if (!sh.privileged()) return;

        Agg.privilegedStarted();
        if (pool == null) pool = Executors.newSingleThreadExecutor();
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    Shell.Result r = sh.exec("dumpsys telephony.registry", 8);
                    Agg.offerPrivileged(Agg.parsePhysicalChannels(r == null ? null : r.out));
                } catch (Throwable e) {
                    // A backend that goes away mid-read is a reason to fall back to the
                    // unprivileged sources, not to lose the screen.
                    Agg.offerPrivileged(null);
                }
            }
        });
    }
}

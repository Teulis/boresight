package com.boresight;

import android.content.Context;
import android.os.Build;
import android.telephony.CellInfo;
import android.telephony.PhysicalChannelConfig;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Carrier aggregation: how many component carriers the modem is actually using, and
 * what each one is.
 *
 * This is the thing Samsung's *#0011# ServiceMode screen shows and almost no ordinary
 * app does. It turns out not to need ServiceMode at all. Android 12 made
 * PhysicalChannelConfig public, and it carries, per component: the band, the downlink
 * and uplink bandwidth, both channel numbers, the physical cell id, the radio
 * technology, and — the useful part — whether that component is the primary serving
 * cell or a secondary one. Count the secondaries and you have the aggregation.
 *
 * Three sources, tried in order, because they fail in different ways and on a given
 * phone and a given network any of them may be the only one that answers:
 *
 *   1. PhysicalChannelConfig. Complete, and the only one that names the bands. Needs
 *      API 31, READ_PHONE_STATE, and a modem/HAL that reports it — not every vendor
 *      does, and the list is empty while out of service.
 *   2. ServiceState.getCellBandwidths(). One entry per configured carrier, so the
 *      length is the component count, but there is nothing but a bandwidth in each.
 *   3. CellInfo.getCellConnectionStatus(). Counts cells the modem marks as secondary
 *      serving. Coarsest of the three and often simply unknown.
 *
 * When none of them answers we say so. "Not reported" and "not aggregated" are
 * different statements and conflating them would make the screen lie on exactly the
 * phones where the question is interesting.
 */
public final class Agg {

    /** One component carrier. */
    public static final class Cc {
        public final boolean primary;
        public final int band;          // -1 unknown
        public final int bandwidthKhz;  // -1 unknown
        public final int arfcn;         // -1 unknown
        public final int pci;           // -1 unknown
        public final int netType;       // TelephonyManager.NETWORK_TYPE_*

        Cc(boolean primary, int band, int bandwidthKhz, int arfcn, int pci, int netType) {
            this.primary = primary;
            this.band = band;
            this.bandwidthKhz = bandwidthKhz;
            this.arfcn = arfcn;
            this.pci = pci;
            this.netType = netType;
        }

        public String bandLabel() {
            if (band <= 0) return "—";
            boolean nr = netType == TelephonyManager.NETWORK_TYPE_NR;
            return (nr ? "n" : "B") + band;
        }

        public String bwLabel() {
            if (bandwidthKhz <= 0) return "—";
            // kHz to MHz, one decimal only when it is not a whole number.
            if (bandwidthKhz % 1000 == 0) return (bandwidthKhz / 1000) + " MHz";
            return String.format(Locale.US, "%.1f MHz", bandwidthKhz / 1000f);
        }

        @Override public String toString() {
            StringBuilder s = new StringBuilder();
            s.append(primary ? "PCC" : "SCC").append("  ").append(bandLabel());
            s.append("  ").append(bwLabel());
            if (arfcn > 0) s.append("  arfcn ").append(arfcn);
            if (pci >= 0)  s.append("  pci ").append(pci);
            return s.toString();
        }
    }

    /** Which of the three sources produced the current answer. */
    public static final String SRC_PHYSICAL  = "physical channel config";
    public static final String SRC_PRIVILEGED= "physical channel config (privileged)";
    public static final String SRC_BANDWIDTH = "service state bandwidths";
    public static final String SRC_CELLINFO  = "cell connection status";
    public static final String SRC_NONE      = "not reported";

    /** How long a privileged read stays good. The modem caches harder than this. */
    private static final long PRIV_FRESH_MS = 3000;

    private static volatile List<Cc> live = Collections.emptyList();
    private static volatile Object watcher;          // a Watcher, on API 31+
    private static volatile String source = SRC_NONE;
    /** What happened when we tried to listen. Shown when nothing else answers. */
    private static volatile String listenState = "not started";

    // The privileged read runs a shell command, so it cannot happen on the thread that
    // is painting. It is fetched in the background and read from here.
    private static volatile List<Cc> priv = Collections.emptyList();
    private static volatile long privAt;
    private static volatile boolean privBusy;
    private static java.util.concurrent.ExecutorService pool;

    private Agg() { }

    // ------------------------------------------------------------------ listening

    /**
     * Begin listening, if the platform allows it. Safe to call repeatedly and safe on
     * an API level that has none of this; it simply does nothing and the read path
     * falls through to the other two sources.
     */
    public static void start(Context c) {
        if (Build.VERSION.SDK_INT < 31) { listenState = "needs Android 12"; return; }
        if (watcher != null) return;
        if (!Perms.has(c, android.Manifest.permission.READ_PHONE_STATE)) {
            listenState = "phone state not granted";
            return;
        }
        try {
            Watcher w = new Watcher();
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) { listenState = "no telephony service"; return; }
            Executor ex = new Executor() {
                @Override public void execute(Runnable r) { r.run(); }
            };
            tm.registerTelephonyCallback(ex, w);
            watcher = w;
            listenState = "listening";
        } catch (SecurityException e) {
            // The documented case. AOSP annotates this callback with
            // READ_PRECISE_PHONE_STATE, which is signature/privileged — an ordinary app
            // cannot hold it. Recording the refusal is the point: it is the difference
            // between "this network is not aggregating" and "this phone will not say".
            listenState = "refused: needs READ_PRECISE_PHONE_STATE (privileged)";
        } catch (Throwable e) {
            // A vendor build that refuses the callback some other way is a reason to use
            // a fallback, not a reason to crash a survey tool.
            listenState = "refused: " + e.getClass().getSimpleName();
        }
    }

    public static void stop(Context c) {
        if (Build.VERSION.SDK_INT < 31 || watcher == null) return;
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) tm.unregisterTelephonyCallback((Watcher) watcher);
        } catch (Throwable ignored) { }
        watcher = null;
    }

    /**
     * Only loaded on API 31 and up. Kept in its own class so that a device below 31
     * never has to resolve PhysicalChannelConfig at all — verifying a class that
     * mentions a missing type is what turns a graceful degradation into a crash.
     */
    private static final class Watcher extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.PhysicalChannelConfigListener {

        @Override public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> cfgs) {
            if (cfgs == null || cfgs.isEmpty()) {
                live = Collections.emptyList();
                return;
            }
            List<Cc> out = new ArrayList<>(cfgs.size());
            for (PhysicalChannelConfig p : cfgs) {
                boolean primary =
                        p.getConnectionStatus() == PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING;
                out.add(new Cc(primary,
                        clean(p.getBand(), PhysicalChannelConfig.BAND_UNKNOWN),
                        clean(p.getCellBandwidthDownlinkKhz(),
                                PhysicalChannelConfig.CELL_BANDWIDTH_UNKNOWN),
                        clean(p.getDownlinkChannelNumber(),
                                PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN),
                        clean(p.getPhysicalCellId(),
                                PhysicalChannelConfig.PHYSICAL_CELL_ID_UNKNOWN),
                        p.getNetworkType()));
            }
            // Primary first, so the list reads the way a person would write it.
            List<Cc> ordered = new ArrayList<>(out.size());
            for (Cc cc : out) if (cc.primary) ordered.add(cc);
            for (Cc cc : out) if (!cc.primary) ordered.add(cc);
            live = ordered;
        }

        private static int clean(int v, int unknown) { return v == unknown ? -1 : v; }
    }

    // ------------------------------------------------------------------ reading

    /**
     * The component carriers, best source first. Never null; an empty list means no
     * source answered, which {@link #source()} will then report as "not reported".
     */
    /**
     * True when a privileged read is worth doing: none in flight, and the last one has
     * aged out. The read itself lives in {@link AggFeed}, which keeps this class free
     * of the shell and therefore testable on a desktop.
     */
    public static boolean privilegedStale() {
        return !privBusy && System.currentTimeMillis() - privAt >= PRIV_FRESH_MS;
    }

    /** Hand back what a privileged read found. An empty list is a valid answer. */
    public static void offerPrivileged(List<Cc> found) {
        priv = found == null ? Collections.<Cc>emptyList() : found;
        privAt = System.currentTimeMillis();
        privBusy = false;
    }

    /** Mark a privileged read as started, so a tick does not launch a second one. */
    public static void privilegedStarted() { privBusy = true; }

    public static List<Cc> components(Context c) {
        // Privileged first when it has answered: it is the only source that carries the
        // band and PCI of a secondary carrier.
        List<Cc> fromShell = priv;
        if (!fromShell.isEmpty()) { source = SRC_PRIVILEGED; return fromShell; }

        List<Cc> fromCallback = live;
        if (!fromCallback.isEmpty()) { source = SRC_PHYSICAL; return fromCallback; }

        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null || !Perms.has(c, android.Manifest.permission.READ_PHONE_STATE)) {
            source = SRC_NONE;
            return Collections.emptyList();
        }

        // 2. Bandwidth array: one entry per configured carrier, nothing else in it.
        try {
            ServiceState st = tm.getServiceState();
            if (st != null) {
                int[] bws = st.getCellBandwidths();
                if (bws != null && bws.length > 0) {
                    // The array carries no bands, but the first entry is the primary
                    // carrier and that is the cell we are registered on — so its band
                    // is knowable from CellInfo even though the secondaries' are not.
                    int pccBand = -1, pccType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                    int[] serving = servingBand(tm);
                    if (serving != null) { pccBand = serving[0]; pccType = serving[1]; }

                    List<Cc> out = new ArrayList<>(bws.length);
                    for (int i = 0; i < bws.length; i++) {
                        boolean primary = i == 0;
                        out.add(new Cc(primary,
                                primary ? pccBand : -1,
                                bws[i] > 0 ? bws[i] : -1, -1, -1,
                                primary ? pccType : TelephonyManager.NETWORK_TYPE_UNKNOWN));
                    }
                    source = SRC_BANDWIDTH;
                    return out;
                }
            }
        } catch (Throwable ignored) { }

        // 3. Cells the modem flags as secondary serving.
        try {
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells != null) {
                int primary = 0, secondary = 0;
                for (CellInfo ci : cells) {
                    int st = ci.getCellConnectionStatus();
                    if (st == CellInfo.CONNECTION_PRIMARY_SERVING) primary++;
                    else if (st == CellInfo.CONNECTION_SECONDARY_SERVING) secondary++;
                }
                if (primary + secondary > 0) {
                    List<Cc> out = new ArrayList<>();
                    for (int i = 0; i < primary; i++) {
                        out.add(new Cc(true, -1, -1, -1, -1,
                                TelephonyManager.NETWORK_TYPE_UNKNOWN));
                    }
                    for (int i = 0; i < secondary; i++) {
                        out.add(new Cc(false, -1, -1, -1, -1,
                                TelephonyManager.NETWORK_TYPE_UNKNOWN));
                    }
                    source = SRC_CELLINFO;
                    return out;
                }
            }
        } catch (Throwable ignored) { }

        source = SRC_NONE;
        return Collections.emptyList();
    }

    // ------------------------------------------------------- privileged parsing

    /**
     * Pull the component carriers out of `dumpsys telephony.registry`.
     *
     * This is the whole point of the privileged path. The physical channel
     * configuration carries the band, the PCI and the bandwidth of every carrier
     * including the secondaries, and an ordinary app is refused it — but the shell is
     * not, and Shizuku runs as the shell. The text looks like:
     *
     *   mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,
     *     mCellBandwidthDownlinkKhz=15000,mNetworkType=LTE_CA,mPhysicalCellId=75,
     *     mBand=2,...}, {mConnectionStatus=SecondaryServing,...}]
     *
     * Brackets are counted rather than matched with a regex, because mContextIds is
     * itself a bracketed list and a greedy or lazy match lands in the wrong place.
     * There is one of these per SIM slot, so the richest one wins: an idle slot
     * reports an empty list and would otherwise overwrite a real answer.
     */
    public static List<Cc> parsePhysicalChannels(String dump) {
        List<Cc> best = new ArrayList<>();
        if (dump == null) return best;

        final String key = "mPhysicalChannelConfigs=[";
        int at = dump.indexOf(key);
        while (at >= 0) {
            int open = at + key.length();
            int depth = 1, i = open;
            while (i < dump.length() && depth > 0) {
                char c = dump.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                i++;
            }
            if (depth == 0) {
                List<Cc> here = parseConfigList(dump.substring(open, i - 1));
                if (here.size() > best.size()) best = here;
            }
            at = dump.indexOf(key, i);
        }
        return best;
    }

    /** The inside of one mPhysicalChannelConfigs list: zero or more {...} blocks. */
    private static List<Cc> parseConfigList(String body) {
        List<Cc> out = new ArrayList<>();
        int at = body.indexOf('{');
        while (at >= 0) {
            int close = body.indexOf('}', at);
            if (close < 0) break;
            Cc cc = parseConfig(body.substring(at + 1, close));
            if (cc != null) out.add(cc);
            at = body.indexOf('{', close);
        }
        // Primary first, so the list reads the way a person would write it.
        List<Cc> ordered = new ArrayList<>(out.size());
        for (Cc cc : out) if (cc.primary) ordered.add(cc);
        for (Cc cc : out) if (!cc.primary) ordered.add(cc);
        return ordered;
    }

    private static Cc parseConfig(String block) {
        String status = field(block, "mConnectionStatus");
        if (status == null) return null;
        boolean primary = status.toLowerCase(Locale.US).startsWith("primary");

        int band = intField(block, "mBand", -1);
        int bw   = intField(block, "mCellBandwidthDownlinkKhz", -1);
        int chan = intField(block, "mDownlinkChannelNumber", -1);
        int pci  = intField(block, "mPhysicalCellId", -1);

        // The modem reports an unset numeric as 0, which is a legal PCI and a legal
        // channel number, so neither can be distinguished from "really zero". Treat
        // zero as unknown on the fields where it is never a real answer: a bandwidth
        // of zero and a band of zero mean nothing, and ARFCN 0 is not in use.
        if (band <= 0) band = -1;
        if (bw <= 0) bw = -1;
        if (chan <= 0) chan = -1;

        // An idle SIM slot still publishes one entry, with everything zeroed. It is a
        // placeholder, not a carrier, and counting it loses: picking the longest list
        // then hands back a stub instead of the slot that is actually aggregating.
        // PCI alone cannot save it, because zero is a legal cell id.
        if (band < 0 && bw < 0 && chan < 0) return null;

        int type = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        String nt = field(block, "mNetworkType");
        if (nt != null) {
            String n = nt.toUpperCase(Locale.US);
            if (n.startsWith("NR")) type = TelephonyManager.NETWORK_TYPE_NR;
            else if (n.startsWith("LTE")) type = TelephonyManager.NETWORK_TYPE_LTE;
        }
        return new Cc(primary, band, bw, chan, pci, type);
    }

    /** The value of key= in a comma-separated block, or null. */
    static String field(String block, String key) {
        int at = block.indexOf(key + "=");
        if (at < 0) return null;
        int from = at + key.length() + 1;
        int to = from;
        while (to < block.length() && block.charAt(to) != ',') to++;
        return block.substring(from, to).trim();
    }

    private static int intField(String block, String key, int def) {
        String v = field(block, key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /**
     * The band and radio technology of the cell we are registered on, as {band, type},
     * or null when nothing reports it. Only valid for the primary carrier.
     */
    private static int[] servingBand(TelephonyManager tm) {
        try {
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells == null) return null;
            for (CellInfo ci : cells) {
                if (!ci.isRegistered()) continue;
                if (Build.VERSION.SDK_INT >= 30 && ci instanceof android.telephony.CellInfoNr) {
                    android.telephony.CellIdentityNr id =
                            (android.telephony.CellIdentityNr) ((android.telephony.CellInfoNr) ci)
                                    .getCellIdentity();
                    int[] b = id.getBands();
                    if (b != null && b.length > 0) {
                        return new int[]{b[0], TelephonyManager.NETWORK_TYPE_NR};
                    }
                } else if (Build.VERSION.SDK_INT >= 30
                        && ci instanceof android.telephony.CellInfoLte) {
                    android.telephony.CellIdentityLte id =
                            ((android.telephony.CellInfoLte) ci).getCellIdentity();
                    int[] b = id.getBands();
                    if (b != null && b.length > 0) {
                        return new int[]{b[0], TelephonyManager.NETWORK_TYPE_LTE};
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Where the last {@link #components} answer came from. */
    public static String source() { return source; }

    /**
     * What happened to the physical-channel listener. Worth showing, because the three
     * reasons it stays quiet are not equivalent: the platform is too old, the callback
     * was refused as privileged, or it is listening and the network genuinely has
     * nothing to report.
     */
    public static String listenState() { return listenState; }

    /** True only when a source answered AND it reported more than one carrier. */
    public static boolean aggregated(Context c) {
        return components(c).size() > 1;
    }

    /**
     * Is this cell one of our own component carriers, and which one?
     *
     * The useful consequence of knowing the PCIs. getAllCellInfo lists the secondary
     * carriers alongside genuine neighbours and gives no hint which is which — this
     * device reports CONNECTION_NONE for every cell, so the public
     * getCellConnectionStatus() cannot separate them. Matching the privileged PCI list
     * against the cell list does, and turns "four nearby cells" into "two of these are
     * mine and two are not", which is a different sentence entirely.
     *
     * @return "PCC", "SCC", or null when this cell is not one of ours — or when we have
     *         no component list to compare against, which is the unprivileged case.
     */
    public static String role(Context c, int pci, int band) {
        if (pci < 0) return null;
        for (Cc cc : components(c)) {
            if (cc.pci != pci) continue;
            // When both bands are known they have to agree: PCI is only unique within
            // a frequency, and a reused PCI on another band is a different cell.
            if (cc.band > 0 && band > 0 && cc.band != band) continue;
            return cc.primary ? "PCC" : "SCC";
        }
        return null;
    }

    /** Total downlink bandwidth across the components, in kHz; -1 if unknown. */
    public static int totalBandwidthKhz(Context c) {
        int sum = 0;
        for (Cc cc : components(c)) if (cc.bandwidthKhz > 0) sum += cc.bandwidthKhz;
        return sum > 0 ? sum : -1;
    }

    /**
     * One line for a readout. Distinguishes the three states that matter: aggregated,
     * demonstrably not aggregated, and nobody will say.
     */
    public static String summary(Context c) {
        List<Cc> ccs = components(c);
        if (ccs.isEmpty()) {
            return "not reported — " + listenState;
        }
        int total = totalBandwidthKhz(c);
        StringBuilder s = new StringBuilder();
        if (ccs.size() == 1) {
            s.append("single carrier");
        } else {
            s.append(ccs.size()).append("CC");
            int sec = 0;
            for (Cc cc : ccs) if (!cc.primary) sec++;
            s.append("  ·  1 primary + ").append(sec).append(" secondary");
        }
        if (total > 0) s.append("  ·  ").append(total / 1000).append(" MHz total");
        return s.toString();
    }

    /** Multi-line detail, for the full dump and the radio screen. */
    public static String detail(Context c) {
        List<Cc> ccs = components(c);
        StringBuilder s = new StringBuilder();
        s.append(summary(c)).append('\n');
        s.append("source: ").append(source()).append('\n');
        for (Cc cc : ccs) s.append("  ").append(cc).append('\n');
        return s.toString();
    }
}

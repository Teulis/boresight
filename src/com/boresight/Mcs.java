package com.boresight;

/**
 * Modulation and coding, as far as a non-privileged app can see it.
 *
 * What is real here: CQI. The handset reports a channel quality indicator and, on
 * Android 12 and up, which CQI table it is using. CQI maps directly onto a modulation
 * order and code rate in 3GPP TS 36.213, so the modulation shown for a live CQI is a
 * lookup, not a guess.
 *
 * What is not available at all: the demodulated symbols. A constellation diagram of the
 * kind airOS draws needs raw IQ out of the baseband, and Android has no API for it at any
 * permission level �?" the samples never leave the modem DSP. The constellation this app
 * draws is therefore synthesised: ideal symbol points for the current modulation order,
 * scattered by the error vector magnitude implied by the measured SINR. It moves with the
 * real channel, and it is honest about being a model of it rather than a capture.
 */
public final class Mcs {

    public static final class Entry {
        public final int cqi;
        public final String modulation;
        /** Code rate �- 1024, as 3GPP tabulates it. */
        public final int codeRate1024;
        public final double efficiency;
        /** Bits per symbol: 2 for QPSK, 4 for 16QAM, 6 for 64QAM, 8 for 256QAM. */
        public final int bitsPerSymbol;

        Entry(int cqi, String mod, int cr, double eff, int bps) {
            this.cqi = cqi; this.modulation = mod; this.codeRate1024 = cr;
            this.efficiency = eff; this.bitsPerSymbol = bps;
        }

        public double codeRate() { return codeRate1024 / 1024.0; }

        public String summary() {
            return modulation + "  r=" + String.format(java.util.Locale.US, "%.2f", codeRate())
                 + "  " + String.format(java.util.Locale.US, "%.2f", efficiency) + " b/s/Hz";
        }
    }

    /** TS 36.213 table 7.2.3-1: the original four-bit CQI table, up to 64QAM. */
    private static final Entry[] TABLE1 = {
        null,
        new Entry(1,  "QPSK",    78, 0.1523, 2),
        new Entry(2,  "QPSK",   120, 0.2344, 2),
        new Entry(3,  "QPSK",   193, 0.3770, 2),
        new Entry(4,  "QPSK",   308, 0.6016, 2),
        new Entry(5,  "QPSK",   449, 0.8770, 2),
        new Entry(6,  "QPSK",   602, 1.1758, 2),
        new Entry(7,  "16QAM",  378, 1.4766, 4),
        new Entry(8,  "16QAM",  490, 1.9141, 4),
        new Entry(9,  "16QAM",  616, 2.4063, 4),
        new Entry(10, "64QAM",  466, 2.7305, 6),
        new Entry(11, "64QAM",  567, 3.3223, 6),
        new Entry(12, "64QAM",  666, 3.9023, 6),
        new Entry(13, "64QAM",  772, 4.5234, 6),
        new Entry(14, "64QAM",  873, 5.1152, 6),
        new Entry(15, "64QAM",  948, 5.5547, 6),
    };

    /** TS 36.213 table 7.2.3-2: the 256QAM-capable table. */
    private static final Entry[] TABLE2 = {
        null,
        new Entry(1,  "QPSK",    78, 0.1523, 2),
        new Entry(2,  "QPSK",   193, 0.3770, 2),
        new Entry(3,  "QPSK",   449, 0.8770, 2),
        new Entry(4,  "16QAM",  378, 1.4766, 4),
        new Entry(5,  "16QAM",  490, 1.9141, 4),
        new Entry(6,  "16QAM",  616, 2.4063, 4),
        new Entry(7,  "64QAM",  466, 2.7305, 6),
        new Entry(8,  "64QAM",  567, 3.3223, 6),
        new Entry(9,  "64QAM",  666, 3.9023, 6),
        new Entry(10, "64QAM",  772, 4.5234, 6),
        new Entry(11, "64QAM",  873, 5.1152, 6),
        new Entry(12, "256QAM", 711, 5.5547, 8),
        new Entry(13, "256QAM", 797, 6.2266, 8),
        new Entry(14, "256QAM", 885, 6.9141, 8),
        new Entry(15, "256QAM", 948, 7.4063, 8),
    };

    /** @param table 1 or 2; anything else is treated as table 1. */
    public static Entry forCqi(int cqi, int table) {
        if (cqi < 1 || cqi > 15) return null;
        return (table == 2 ? TABLE2 : TABLE1)[cqi];
    }

    /**
     * The highest modulation a measured SINR can realistically carry, as a fallback when
     * the modem will not report CQI. Thresholds are the usual rules of thumb for roughly
     * 10 percent block error at a middling code rate, not a specification.
     */
    public static String modulationForSinr(int sinrDb) {
        if (!Sampler.has(sinrDb)) return null;
        if (sinrDb >= 22) return "256QAM";
        if (sinrDb >= 15) return "64QAM";
        if (sinrDb >= 8)  return "16QAM";
        if (sinrDb >= -2) return "QPSK";
        return "below QPSK";
    }

    public static int bitsFor(String modulation) {
        if (modulation == null) return 2;
        switch (modulation) {
            case "256QAM": return 8;
            case "64QAM":  return 6;
            case "16QAM":  return 4;
            default:       return 2;
        }
    }

    /** Points per side of the square constellation: 2 for QPSK, 4, 8, 16. */
    public static int sideFor(int bitsPerSymbol) {
        return 1 << (bitsPerSymbol / 2);
    }

    /**
     * Error vector magnitude implied by a signal-to-noise ratio, as a fraction of the
     * RMS symbol amplitude. EVM_rms �?^ 10^(�^'SNR/20) when the dominant impairment is
     * additive noise, which at these SINRs it is.
     */
    public static double evmFromSinr(double sinrDb) {
        return Math.pow(10, -sinrDb / 20.0);
    }

    /** EVM as the percentage figure test gear quotes. */
    public static double evmPercent(double sinrDb) {
        return evmFromSinr(sinrDb) * 100.0;
    }

    /**
     * Rough physical-layer throughput ceiling: spectral efficiency across the carrier,
     * before any MIMO gain, protocol overhead or scheduler sharing. Mbit/s.
     */
    public static double ceilingMbps(double efficiency, int bandwidthKhz, int layers) {
        if (efficiency <= 0 || !Sampler.has(bandwidthKhz) || bandwidthKhz <= 0) return Double.NaN;
        double hz = bandwidthKhz * 1000.0;
        // About 10 percent goes to reference signals, control and guard bands.
        return efficiency * hz * 0.9 * Math.max(1, layers) / 1e6;
    }

    /**
     * Shannon limit for the measured SINR, as a sanity bound on the CQI figure.
     */
    public static double shannonEfficiency(double sinrDb) {
        double snr = Math.pow(10, sinrDb / 10.0);
        return Math.log(1 + snr) / Math.log(2);
    }

    /** How close the scheduler is running to theory, as a percentage. */
    public static double efficiencyRatio(double achieved, double sinrDb) {
        double lim = shannonEfficiency(sinrDb);
        return lim <= 0 ? Double.NaN : achieved / lim * 100.0;
    }

    private Mcs() {}
}

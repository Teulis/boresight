package com.boresight;

import android.content.Context;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Frequency maths and a full-detail text dump. */
final class Rf {

    private Rf() {}

    static boolean has(int v) { return v != Integer.MAX_VALUE && v != Integer.MIN_VALUE; }

    static String num(int v) { return has(v) ? String.valueOf(v) : "-"; }

    static String num(long v) { return v == Long.MAX_VALUE ? "-" : String.valueOf(v); }

    static String str(CharSequence s) {
        return s == null || s.length() == 0 ? "-" : s.toString();
    }

    // ----------------------------------------------------------- frequencies

    /**
     * E-UTRA band -> { band, downlink low edge (tenths of MHz), downlink EARFCN offset,
     *                  uplink low edge (tenths of MHz; 0 = TDD, uplink equals downlink;
     *                  -1 = downlink only) }.
     */
    private static final int[][] LTE_BANDS = {
        {1, 2110_0, 0, 1920_0},      {2, 1930_0, 600, 1850_0},
        {3, 1805_0, 1200, 1710_0},   {4, 2110_0, 1950, 1710_0},
        {5, 869_0, 2400, 824_0},     {7, 2620_0, 2750, 2500_0},
        {8, 925_0, 3450, 880_0},     {12, 729_0, 5010, 699_0},
        {13, 746_0, 5180, 777_0},    {14, 758_0, 5280, 788_0},
        {17, 734_0, 5730, 704_0},    {18, 860_0, 5850, 815_0},
        {19, 875_0, 6000, 830_0},    {20, 791_0, 6150, 832_0},
        {21, 1495_9, 6450, 1447_9},  {25, 1930_0, 8040, 1850_0},
        {26, 859_0, 8690, 814_0},    {28, 758_0, 9210, 703_0},
        {29, 717_0, 9660, -1},       {30, 2350_0, 9770, 2305_0},
        {32, 1452_0, 9920, -1},      {38, 2570_0, 37750, 0},
        {39, 1880_0, 38250, 0},      {40, 2300_0, 38650, 0},
        {41, 2496_0, 39650, 0},      {42, 3400_0, 41590, 0},
        {43, 3600_0, 43590, 0},      {46, 5150_0, 46790, 0},
        {48, 3550_0, 55240, 0},      {66, 2110_0, 66436, 1710_0},
        {71, 617_0, 68586, 663_0},
    };

    private static int[] lteBand(int band) {
        for (int[] b : LTE_BANDS) if (b[0] == band) return b;
        return null;
    }

    /**
     * Uplink centre frequency for an LTE band and its downlink EARFCN, in MHz.
     * The EARFCN offset cancels, so this is F_UL_low + 0.1 * (EARFCN_DL - N_offs_DL).
     */
    static double lteUlMhz(int band, int earfcnDl) {
        int[] b = lteBand(band);
        if (b == null || !has(earfcnDl)) return Double.NaN;
        if (b[3] == 0) return lteMhz(band, earfcnDl);   // TDD: same channel
        if (b[3] < 0) return Double.NaN;                // downlink only
        return b[3] / 10.0 + 0.1 * (earfcnDl - b[2]);
    }

    /** Uplink EARFCN for a downlink EARFCN, or -1. */
    static int lteUlEarfcn(int band, int earfcnDl) {
        int[] b = lteBand(band);
        if (b == null || !has(earfcnDl) || b[3] < 0) return -1;
        if (b[3] == 0) return earfcnDl;
        int ulOffset = ulOffset(band);
        return ulOffset < 0 ? -1 : ulOffset + (earfcnDl - b[2]);
    }

    /** N_offs-UL per band. */
    private static int ulOffset(int band) {
        switch (band) {
            case 1: return 18000;   case 2: return 18600;   case 3: return 19200;
            case 4: return 19950;   case 5: return 20400;   case 7: return 20750;
            case 8: return 21450;   case 12: return 23010;  case 13: return 23180;
            case 14: return 23280;  case 17: return 23730;  case 18: return 23850;
            case 19: return 24000;  case 20: return 24150;  case 21: return 24450;
            case 25: return 26040;  case 26: return 26690;  case 28: return 27210;
            case 30: return 27660;  case 66: return 131972; case 71: return 133122;
            default: return -1;
        }
    }

    /** Duplex spacing in MHz, or NaN for TDD / unknown. */
    static double duplexSpacing(int band) {
        int[] b = lteBand(band);
        if (b == null || b[3] <= 0) return Double.NaN;
        return (b[1] - b[3]) / 10.0;
    }

    /** True when the band is TDD, so uplink and downlink share the channel. */
    static boolean isTdd(int band) {
        int[] b = lteBand(band);
        return b != null && b[3] == 0;
    }

    /**
     * Downlink centre frequency for an LTE EARFCN, in MHz.
     * F = F_low + 0.1 * (EARFCN - N_offset). Returns NaN when the band is unknown.
     */
    static double lteMhz(int band, int earfcn) {
        if (!has(earfcn)) return Double.NaN;
        for (int[] b : LTE_BANDS) {
            if (b[0] == band) return b[1] / 10.0 + 0.1 * (earfcn - b[2]);
        }
        return Double.NaN;
    }

    /** Band whose EARFCN range contains this value, or -1. Used when bands aren't reported. */
    static int lteBandFor(int earfcn) {
        if (!has(earfcn)) return -1;
        int best = -1, bestOff = Integer.MAX_VALUE;
        for (int[] b : LTE_BANDS) {
            int off = earfcn - b[2];
            if (off >= 0 && off < 2000 && off < bestOff) { bestOff = off; best = b[0]; }
        }
        return best;
    }

    /**
     * NR-ARFCN to MHz. Global raster, no band table needed:
     *   N  &lt; 600000      -> 5 kHz steps from 0
     *   N  &lt; 2016667     -> 15 kHz steps from 3000 MHz
     *   otherwise          -> 60 kHz steps from 24250.08 MHz
     */
    static double nrMhz(int nrarfcn) {
        if (!has(nrarfcn)) return Double.NaN;
        if (nrarfcn < 600000) return 0.005 * nrarfcn;
        if (nrarfcn < 2016667) return 3000.0 + 0.015 * (nrarfcn - 600000);
        return 24250.08 + 0.06 * (nrarfcn - 2016667);
    }

    /** UMTS UARFCN -> MHz (downlink), 200 kHz raster. */
    static double wcdmaMhz(int uarfcn) {
        return has(uarfcn) ? uarfcn / 5.0 : Double.NaN;
    }

    /**
     * NR uplink for an FDD band: downlink minus the band's duplex spacing.
     * NR FDD bands reuse the E-UTRA band plan, so the LTE table applies.
     */
    static double nrUlMhz(int band, double dlMhz) {
        double sp = duplexSpacing(band);
        if (Double.isNaN(sp) || Double.isNaN(dlMhz)) return Double.NaN;
        return dlMhz - sp;
    }

    static String mhz(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.US, "%.1f MHz", v);
    }

    // ------------------------------------------------------------ full dump

    static String dump(Context ctx, TelephonyManager tm) {
        StringBuilder s = new StringBuilder();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        s.append("Cell Monitor detail  ").append(stamp).append("\n");
        s.append("================================================\n\n");

        s.append("NETWORK\n");
        kv(s, "Carrier (network)", str(tm.getNetworkOperatorName()));
        kv(s, "Network PLMN", str(tm.getNetworkOperator()));
        kv(s, "Carrier (SIM)", str(tm.getSimOperatorName()));
        kv(s, "SIM PLMN", str(tm.getSimOperator()));
        kv(s, "SIM country", str(tm.getSimCountryIso()));
        kv(s, "Network country", str(tm.getNetworkCountryIso()));
        try { kv(s, "Roaming", String.valueOf(tm.isNetworkRoaming())); } catch (Exception ignored) {}
        kv(s, "Data network", netName(tm.getDataNetworkType()));
        kv(s, "Voice network", netName(tm.getVoiceNetworkType()));
        kv(s, "Data state", dataState(tm.getDataState()));
        kv(s, "SIM state", simState(tm.getSimState()));
        kv(s, "Phone type", phoneType(tm.getPhoneType()));
        try {
            kv(s, "Subscription id", String.valueOf(SubscriptionManager.getDefaultDataSubscriptionId()));
        } catch (Exception ignored) {}
        s.append('\n');

        try {
            SignalStrength ss = tm.getSignalStrength();
            if (ss != null) {
                s.append("SIGNAL STRENGTH\n");
                kv(s, "Overall level", ss.getLevel() + " / 4");
                for (CellSignalStrength c : ss.getCellSignalStrengths()) {
                    kv(s, c.getClass().getSimpleName().replace("CellSignalStrength", ""),
                            c.getDbm() + " dBm   level " + c.getLevel() + " / 4   asu " + c.getAsuLevel());
                }
                s.append('\n');
            }
        } catch (Exception ignored) {}

        s.append("CARRIER AGGREGATION\n");
        for (String line : Agg.detail(ctx).split("\n")) {
            if (!line.trim().isEmpty()) s.append("  ").append(line).append('\n');
        }
        s.append('\n');

        List<CellInfo> cells = null;
        try { cells = tm.getAllCellInfo(); } catch (SecurityException ignored) {}
        if (cells == null || cells.isEmpty()) {
            s.append("CELLS\n  none reported\n");
            return s.toString();
        }

        int bwFallback = bandwidthKhz(tm);
        int i = 0;
        for (CellInfo c : cells) {
            s.append(c.isRegistered() ? "SERVING CELL" : "NEIGHBOUR " + (++i)).append('\n');
            dumpCell(s, c, c.isRegistered() ? bwFallback : Integer.MAX_VALUE);
            s.append('\n');
        }
        return s.toString();
    }

    /**
     * Channel bandwidth in kHz. CellIdentity often reports it as unavailable, in which
     * case ServiceState still carries it for the serving cell.
     */
    static int bandwidthKhz(TelephonyManager tm) {
        try {
            android.telephony.ServiceState st = tm.getServiceState();
            if (st != null) {
                int[] bws = st.getCellBandwidths();
                if (bws != null && bws.length > 0 && bws[0] > 0) return bws[0];
            }
        } catch (Exception ignored) {}
        return Integer.MAX_VALUE;
    }

    static String bw(int fromIdentity, int fallbackKhz) {
        int v = has(fromIdentity) && fromIdentity > 0 ? fromIdentity : fallbackKhz;
        return has(v) && v > 0 ? (v / 1000) + " MHz" : "-";
    }

    private static void dumpCell(StringBuilder s, CellInfo c, int bwFallback) {
        kv(s, "Registered", String.valueOf(c.isRegistered()));
        long age = c.getTimeStamp();
        kv(s, "Timestamp", String.valueOf(age));

        if (c instanceof CellInfoLte) {
            CellIdentityLte id = ((CellInfoLte) c).getCellIdentity();
            CellSignalStrengthLte ss = ((CellInfoLte) c).getCellSignalStrength();
            int earfcn = id.getEarfcn();
            int[] bands = id.getBands();
            int band = bands != null && bands.length > 0 ? bands[0] : lteBandFor(earfcn);
            kv(s, "Technology", "LTE");
            kv(s, "Operator", str(id.getOperatorAlphaLong()) + " / " + str(id.getOperatorAlphaShort()));
            kv(s, "MCC / MNC", str(id.getMccString()) + " / " + str(id.getMncString()));
            kv(s, "CI", num(id.getCi()));
            if (has(id.getCi())) {
                kv(s, "eNodeB", String.valueOf(id.getCi() >> 8));
                kv(s, "Sector", String.valueOf(id.getCi() & 0xFF));
            }
            kv(s, "PCI", num(id.getPci()));
            kv(s, "TAC", num(id.getTac()));
            kv(s, "EARFCN (DL)", num(earfcn));
            int ul = lteUlEarfcn(band, earfcn);
            kv(s, "EARFCN (UL)", ul < 0 ? "-" : String.valueOf(ul));
            kv(s, "Band", band > 0 ? String.valueOf(band) : "-");
            kv(s, "Duplex", band > 0 ? (isTdd(band) ? "TDD" : "FDD") : "-");
            kv(s, "Frequency (DL)", mhz(lteMhz(band, earfcn)));
            kv(s, "Frequency (UL)", mhz(lteUlMhz(band, earfcn)));
            double sp = duplexSpacing(band);
            kv(s, "Duplex spacing", Double.isNaN(sp) ? "-" : String.format(Locale.US, "%.1f MHz", sp));
            kv(s, "Bandwidth", bw(id.getBandwidth(), bwFallback));
            kv(s, "RSRP", num(ss.getRsrp()) + " dBm");
            kv(s, "RSRQ", num(ss.getRsrq()) + " dB");
            kv(s, "RSSNR", num(ss.getRssnr()) + " dB");
            kv(s, "RSSI", num(ss.getRssi()) + " dBm");
            kv(s, "CQI", num(ss.getCqi()));
            kv(s, "Timing advance", num(ss.getTimingAdvance()));
            kv(s, "Level", ss.getLevel() + " / 4");

        } else if (c instanceof CellInfoNr) {
            CellIdentityNr id = (CellIdentityNr) c.getCellIdentity();
            CellSignalStrengthNr ss = (CellSignalStrengthNr) c.getCellSignalStrength();
            int arfcn = id.getNrarfcn();
            int[] bands = id.getBands();
            kv(s, "Technology", "5G NR");
            kv(s, "Operator", str(id.getOperatorAlphaLong()) + " / " + str(id.getOperatorAlphaShort()));
            kv(s, "MCC / MNC", str(id.getMccString()) + " / " + str(id.getMncString()));
            kv(s, "NCI", num(id.getNci()));
            kv(s, "PCI", num(id.getPci()));
            kv(s, "TAC", num(id.getTac()));
            int nband = bands != null && bands.length > 0 ? bands[0] : -1;
            kv(s, "NRARFCN", num(arfcn));
            kv(s, "Band", nband > 0 ? "n" + nband : "-");
            kv(s, "Duplex", nband > 0 ? (isTdd(nband) ? "TDD" : "FDD") : "-");
            kv(s, "Frequency (DL)", mhz(nrMhz(arfcn)));
            double nrUl = isTdd(nband) ? nrMhz(arfcn) : nrUlMhz(nband, nrMhz(arfcn));
            kv(s, "Frequency (UL)", mhz(nrUl));
            kv(s, "Bandwidth", bw(Integer.MAX_VALUE, bwFallback));
            kv(s, "SS-RSRP", num(ss.getSsRsrp()) + " dBm");
            kv(s, "SS-RSRQ", num(ss.getSsRsrq()) + " dB");
            kv(s, "SS-SINR", num(ss.getSsSinr()) + " dB");
            kv(s, "CSI-RSRP", num(ss.getCsiRsrp()) + " dBm");
            kv(s, "CSI-RSRQ", num(ss.getCsiRsrq()) + " dB");
            kv(s, "CSI-SINR", num(ss.getCsiSinr()) + " dB");
            kv(s, "Level", ss.getLevel() + " / 4");

        } else if (c instanceof CellInfoWcdma) {
            CellIdentityWcdma id = ((CellInfoWcdma) c).getCellIdentity();
            CellSignalStrengthWcdma ss = ((CellInfoWcdma) c).getCellSignalStrength();
            kv(s, "Technology", "WCDMA");
            kv(s, "Operator", str(id.getOperatorAlphaLong()) + " / " + str(id.getOperatorAlphaShort()));
            kv(s, "MCC / MNC", str(id.getMccString()) + " / " + str(id.getMncString()));
            kv(s, "CID", num(id.getCid()));
            kv(s, "LAC", num(id.getLac()));
            kv(s, "PSC", num(id.getPsc()));
            kv(s, "UARFCN", num(id.getUarfcn()));
            kv(s, "Frequency (DL)", mhz(wcdmaMhz(id.getUarfcn())));
            kv(s, "RSCP", num(ss.getDbm()) + " dBm");
            kv(s, "Level", ss.getLevel() + " / 4");

        } else if (c instanceof CellInfoGsm) {
            CellIdentityGsm id = ((CellInfoGsm) c).getCellIdentity();
            CellSignalStrengthGsm ss = ((CellInfoGsm) c).getCellSignalStrength();
            kv(s, "Technology", "GSM");
            kv(s, "Operator", str(id.getOperatorAlphaLong()) + " / " + str(id.getOperatorAlphaShort()));
            kv(s, "MCC / MNC", str(id.getMccString()) + " / " + str(id.getMncString()));
            kv(s, "CID", num(id.getCid()));
            kv(s, "LAC", num(id.getLac()));
            kv(s, "ARFCN", num(id.getArfcn()));
            kv(s, "BSIC", num(id.getBsic()));
            kv(s, "RSSI", num(ss.getDbm()) + " dBm");
            kv(s, "Level", ss.getLevel() + " / 4");
        } else {
            kv(s, "Technology", c.getClass().getSimpleName().replace("CellInfo", ""));
        }
    }

    private static void kv(StringBuilder s, String k, String v) {
        s.append("  ").append(pad(k, 18)).append(v).append('\n');
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    static String netName(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR:      return "NR (5G)";
            case TelephonyManager.NETWORK_TYPE_LTE:     return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP:   return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_HSPA:    return "HSPA";
            case TelephonyManager.NETWORK_TYPE_HSDPA:   return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA:   return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_UMTS:    return "UMTS";
            case TelephonyManager.NETWORK_TYPE_EDGE:    return "EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS:    return "GPRS";
            case TelephonyManager.NETWORK_TYPE_IWLAN:   return "IWLAN";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "none";
            default: return "type " + t;
        }
    }

    private static String dataState(int s) {
        switch (s) {
            case TelephonyManager.DATA_CONNECTED:     return "connected";
            case TelephonyManager.DATA_CONNECTING:    return "connecting";
            case TelephonyManager.DATA_DISCONNECTED:  return "disconnected";
            case TelephonyManager.DATA_SUSPENDED:     return "suspended";
            default: return String.valueOf(s);
        }
    }

    private static String simState(int s) {
        switch (s) {
            case TelephonyManager.SIM_STATE_READY:            return "ready";
            case TelephonyManager.SIM_STATE_ABSENT:           return "absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:     return "PIN required";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:     return "PUK required";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:   return "network locked";
            case TelephonyManager.SIM_STATE_NOT_READY:        return "not ready";
            case TelephonyManager.SIM_STATE_PERM_DISABLED:    return "permanently disabled";
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR:    return "card IO error";
            case TelephonyManager.SIM_STATE_CARD_RESTRICTED:  return "card restricted";
            default: return String.valueOf(s);
        }
    }

    private static String phoneType(int t) {
        switch (t) {
            case TelephonyManager.PHONE_TYPE_GSM:  return "GSM";
            case TelephonyManager.PHONE_TYPE_CDMA: return "CDMA";
            case TelephonyManager.PHONE_TYPE_SIP:  return "SIP";
            case TelephonyManager.PHONE_TYPE_NONE: return "none";
            default: return String.valueOf(t);
        }
    }
}

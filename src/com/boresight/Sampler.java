package com.boresight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns whatever the modem will tell us into plain rows, once, with no UI attached.
 * Shared by the live screen, the service, the notification, the widget and the tile so
 * they can never disagree about what the radio is doing.
 */
public final class Sampler {

    public static class Cell {
        public String tech = "?", carrier, mcc, mnc, ident;
        public int dbm = Integer.MAX_VALUE, qual = Integer.MAX_VALUE;
        public int pci = Integer.MAX_VALUE, tac = Integer.MAX_VALUE;
        public int arfcn = Integer.MAX_VALUE, band = Integer.MAX_VALUE;
        /** LTE timing advance, in 16·Ts units. Each step is about 78 m of range. */
        public int ta = Integer.MAX_VALUE;
        /** Channel quality indicator and which 3GPP table it indexes (1 or 2). */
        public int cqi = Integer.MAX_VALUE, cqiTable = Integer.MAX_VALUE;
        public int rsrq = Integer.MAX_VALUE, rssi = Integer.MAX_VALUE;
        /**
         * How old this reading was when it was taken, in ms, from the modem's own
         * stamp. The platform serves cell info from a cache and does not guarantee a
         * refresh, so a sighting can be seconds stale with nothing to show for it.
         */
        public int ageMs = Integer.MAX_VALUE;
        public double dlMhz = Double.NaN, ulMhz = Double.NaN;
        public int bwKhz = Integer.MAX_VALUE;
        public boolean serving;
        public String detail = "";
    }

    public static class Snap {
        public long ts = System.currentTimeMillis();
        public String operator = "-", plmn = "-", netType = "?";
        public boolean roaming;
        /**
         * Serving cell is a satellite rather than a tower.
         *
         * Worth a field of its own because nothing else gives it away. T-Satellite runs
         * on T-Mobile's PCS G-block, which is LTE band 25, so a survey that does not ask
         * records a satellite pass as an ordinary terrestrial cell on B25 — a tower that
         * is not there, at a position that means nothing.
         */
        public boolean ntn;
        public final List<Cell> cells = new ArrayList<>();
        public Cell serving;

        public int dbm() { return serving != null ? serving.dbm : Integer.MAX_VALUE; }

        public String title() {
            if (serving == null) return "-".equals(operator) ? "No service" : operator;
            String c = serving.carrier != null ? serving.carrier : operator;
            return ("-".equals(c) || c == null ? "No service" : c) + "  ·  " + netType;
        }

        public int neighbours() {
            int n = 0;
            for (Cell c : cells) if (!c.serving) n++;
            return n;
        }
    }

    private final Context ctx;
    private final TelephonyManager tm;
    private final android.content.SharedPreferences learned;

    public Sampler(Context c) {
        ctx = c.getApplicationContext();
        tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        learned = ctx.getSharedPreferences("carriers", Context.MODE_PRIVATE);
    }

    public boolean permitted() {
        return ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED
            && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
    }

    public Snap sample() {
        Snap s = new Snap();
        if (tm == null || !permitted()) return s;

        s.operator = blank(tm.getNetworkOperatorName());
        s.plmn = blank(tm.getNetworkOperator());
        s.netType = netName();
        try { s.roaming = tm.isNetworkRoaming(); } catch (Exception ignored) {}
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            try {
                android.telephony.ServiceState st = tm.getServiceState();
                if (st != null) s.ntn = st.isUsingNonTerrestrialNetwork();
            } catch (Throwable ignored) { }
        }

        int bwFallback = Rf.bandwidthKhz(tm);

        List<CellInfo> all = null;
        try { all = tm.getAllCellInfo(); } catch (Exception ignored) {}
        if (all == null) return s;

        for (CellInfo info : all) {
            Cell c = describe(info, info.isRegistered(), bwFallback);
            if (c == null) continue;
            s.cells.add(c);
            if (c.serving) {
                // Remember which carrier owns this frequency; neighbours carry no PLMN.
                if (!"-".equals(s.operator) && has(c.arfcn)) {
                    learned.edit().putString("f" + c.arfcn, s.operator).apply();
                }
                if (s.serving == null || (has(c.dbm) && !has(s.serving.dbm))) s.serving = c;
            }
        }
        if (s.serving == null) {
            for (Cell c : s.cells) if (has(c.dbm)) { s.serving = c; break; }
        }
        return s;
    }

    /**
     * Describe a cell that arrived outside a normal sweep — a network scan result, for
     * instance, where the modem reports cells it is not camped on.
     */
    public Cell describeOne(CellInfo info) {
        return describe(info, info.isRegistered(), Integer.MAX_VALUE);
    }
    // ------------------------------------------------------------------ per-cell

    private Cell describe(CellInfo info, boolean serving, int bwFallback) {
        Cell c = new Cell();
        c.serving = serving;

        // How old this reading is, which nothing on the market records.
        //
        // getAllCellInfo is served from a cache by default and the platform documents
        // that refreshes are rate-limited and "not guaranteed", while telling callers to
        // check getTimeStamp(). A survey point stamped with the clock but measured nine
        // seconds earlier is a different fact from one measured now, and without this
        // there is no way to tell them apart afterwards — which matters most in exactly
        // the case the survey is evidence for.
        try {
            long stamped = info.getTimestampMillis();
            long ageMs = System.currentTimeMillis() - stamped;
            // A zero stamp means the modem did not say; a wild one means the clocks do
            // not agree. Either way that is "unknown", not a number to record.
            if (stamped > 0 && ageMs >= 0 && ageMs < 600_000) c.ageMs = (int) ageMs;
        } catch (Throwable ignored) { }

        if (info instanceof CellInfoNr) {
            CellIdentityNr id = (CellIdentityNr) info.getCellIdentity();
            CellSignalStrengthNr ss = (CellSignalStrengthNr) info.getCellSignalStrength();
            c.tech = "NR";
            c.dbm = ss.getSsRsrp();
            c.qual = ss.getSsSinr();
            c.arfcn = id.getNrarfcn();
            c.pci = id.getPci();
            c.tac = id.getTac();
            c.mcc = id.getMccString(); c.mnc = id.getMncString();
            int[] b = id.getBands();
            c.band = b != null && b.length > 0 ? b[0] : Integer.MAX_VALUE;
            c.dlMhz = Rf.nrMhz(id.getNrarfcn());
            c.ulMhz = Rf.isTdd(c.band) ? c.dlMhz : Rf.nrUlMhz(c.band, c.dlMhz);
            c.bwKhz = serving ? bwFallback : Integer.MAX_VALUE;
            c.rsrq = ss.getSsRsrq();
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                java.util.List<Integer> rep = ss.getCsiCqiReport();
                if (rep != null && !rep.isEmpty()) {
                    c.cqi = rep.get(0);
                    c.cqiTable = ss.getCsiCqiTableIndex();
                }
            }
            c.ident = "nr:" + id.getNci();
            c.detail = "NCI " + id.getNci() + fld("  PCI", id.getPci())
                     + fld("  TAC", id.getTac());
        } else if (info instanceof CellInfoLte) {
            CellIdentityLte id = ((CellInfoLte) info).getCellIdentity();
            CellSignalStrengthLte ss = ((CellInfoLte) info).getCellSignalStrength();
            c.tech = "LTE";
            c.dbm = ss.getRsrp();
            c.qual = ss.getRssnr();
            c.arfcn = id.getEarfcn();
            c.pci = id.getPci();
            c.tac = id.getTac();
            c.mcc = id.getMccString(); c.mnc = id.getMncString();
            int[] b = id.getBands();
            c.band = b != null && b.length > 0 ? b[0] : Rf.lteBandFor(id.getEarfcn());
            c.dlMhz = Rf.lteMhz(c.band, id.getEarfcn());
            c.ulMhz = Rf.lteUlMhz(c.band, id.getEarfcn());
            c.bwKhz = has(id.getBandwidth()) ? id.getBandwidth()
                    : (serving ? bwFallback : Integer.MAX_VALUE);
            c.ta = ss.getTimingAdvance();
            c.rsrq = ss.getRsrq();
            c.rssi = ss.getRssi();
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                c.cqi = ss.getCqi();
                c.cqiTable = ss.getCqiTableIndex();
            }
            int ci = id.getCi();
            c.ident = has(ci) ? "lte:" + ci : "lte:pci" + id.getPci() + "@" + id.getEarfcn();
            c.detail = (has(ci) ? "CI " + ci + "  eNB " + (ci >> 8) + "/" + (ci & 0xFF) : "")
                     + fld("  PCI", id.getPci()) + fld("  TAC", id.getTac())
                     + fld("  TA", ss.getTimingAdvance());
        } else if (info instanceof CellInfoWcdma) {
            CellIdentityWcdma id = ((CellInfoWcdma) info).getCellIdentity();
            CellSignalStrengthWcdma ss = ((CellInfoWcdma) info).getCellSignalStrength();
            c.tech = "WCDMA";
            c.dbm = ss.getDbm();
            c.arfcn = id.getUarfcn();
            c.pci = id.getPsc();
            c.tac = id.getLac();
            c.mcc = id.getMccString(); c.mnc = id.getMncString();
            c.ident = has(id.getCid()) ? "wcdma:" + id.getCid()
                    : "wcdma:psc" + id.getPsc() + "@" + id.getUarfcn();
            c.detail = fld("CID", id.getCid()) + fld("  LAC", id.getLac())
                     + fld("  PSC", id.getPsc());
        } else if (info instanceof CellInfoGsm) {
            CellIdentityGsm id = ((CellInfoGsm) info).getCellIdentity();
            CellSignalStrengthGsm ss = ((CellInfoGsm) info).getCellSignalStrength();
            c.tech = "GSM";
            c.dbm = ss.getDbm();
            c.arfcn = id.getArfcn();
            c.tac = id.getLac();
            c.mcc = id.getMccString(); c.mnc = id.getMncString();
            c.ident = has(id.getCid()) ? "gsm:" + id.getCid()
                    : "gsm:arfcn" + id.getArfcn();
            c.detail = fld("CID", id.getCid()) + fld("  LAC", id.getLac())
                     + fld("  BSIC", id.getBsic());
        } else {
            return null;
        }
        c.carrier = carrierFor(c);
        return c;
    }

    private String carrierFor(Cell c) {
        if (c.mcc != null && c.mnc != null) {
            String known = Plmn.name(c.mcc + c.mnc);
            if (known != null) return known;
            return c.mcc + "-" + c.mnc;
        }
        return has(c.arfcn) ? learned.getString("f" + c.arfcn, null) : null;
    }

    /** Carriers the app has attributed to a frequency so far, for the filter tabs. */
    public List<String> knownCarriers() {
        List<String> out = new ArrayList<>();
        for (Object v : learned.getAll().values()) {
            String s = v == null ? null : v.toString();
            if (s != null && !s.isEmpty() && !out.contains(s)) out.add(s);
        }
        java.util.Collections.sort(out);
        return out;
    }

    public void forgetCarriers() { learned.edit().clear().apply(); }

    private String netName() {
        int t;
        try { t = tm.getDataNetworkType(); } catch (Exception e) { return "?"; }
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR:      return "NR";
            case TelephonyManager.NETWORK_TYPE_LTE:     return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP:   return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_HSPA:    return "HSPA";
            case TelephonyManager.NETWORK_TYPE_UMTS:    return "UMTS";
            case TelephonyManager.NETWORK_TYPE_EDGE:    return "EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS:    return "GPRS";
            case TelephonyManager.NETWORK_TYPE_IWLAN:   return "Wi-Fi call";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "none";
            default: return "type " + t;
        }
    }

    static boolean has(int v) { return v != Integer.MAX_VALUE && v != Integer.MIN_VALUE; }

    private static String fld(String k, int v) { return has(v) ? k + " " + v : ""; }

    private static String blank(String s) { return s == null || s.isEmpty() ? "-" : s; }
}

package com.boresight;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Taking the log off the phone, only ever because the user asked.
 *
 * Three shapes: a plain CSV with every column, GeoJSON for anything that reads a map
 * layer, and the CSV header WiGLE expects for people who already use that pipeline.
 * Files land in Downloads through MediaStore, so no storage permission is needed and the
 * user can see exactly what was written.
 */
public final class Export {

    public static class Result {
        public boolean ok;
        public String where;
        public int rows;
        public String error;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------------ writers

    public static Result csv(Context ctx, List<Store.Obs> rows) {
        Result r = new Result();
        try {
            StringBuilder b = new StringBuilder();
            // Columns are appended, never reordered or renamed: somebody's spreadsheet
            // or script is reading an earlier export by position.
            b.append("timestamp,iso_time,kind,tech,carrier,mcc,mnc,identity,pci,tac,"
                   + "arfcn,band,freq_mhz,dbm,quality,timing_advance,serving,name,mac,"
                   + "lat,lon,accuracy_m,altitude_m,speed_mps,bearing_deg,"
                   + "rsrq_db,rssi_dbm,cqi,bandwidth_khz,sample_age_ms,"
                   + "carriers,carrier_bw_khz,satellite\n");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            for (Store.Obs o : rows) {
                b.append(o.ts).append(',')
                 .append(iso.format(new Date(o.ts))).append(',')
                 .append(q(o.kind)).append(',')
                 .append(q(o.tech)).append(',')
                 .append(q(o.carrier)).append(',')
                 .append(q(o.mcc)).append(',')
                 .append(q(o.mnc)).append(',')
                 .append(q(o.ident)).append(',')
                 .append(n(o.pci)).append(',')
                 .append(n(o.tac)).append(',')
                 .append(n(o.arfcn)).append(',')
                 .append(n(o.band)).append(',')
                 .append(Double.isNaN(o.dlMhz) ? "" : fmt(o.dlMhz)).append(',')
                 .append(n(o.dbm)).append(',')
                 .append(n(o.qual)).append(',')
                 .append(n(o.ta)).append(',')
                 .append(o.serving ? 1 : 0).append(',')
                 .append(q(o.name)).append(',')
                 .append(q(o.mac)).append(',')
                 .append(o.located() ? fmt(o.lat) : "").append(',')
                 .append(o.located() ? fmt(o.lon) : "").append(',')
                 .append(o.acc == 0 ? "" : fmt(o.acc)).append(',')
                 .append(o.alt == 0 ? "" : fmt(o.alt)).append(',')
                 .append(o.speed == 0 ? "" : fmt(o.speed)).append(',')
                 .append(o.bearing == 0 ? "" : fmt(o.bearing)).append(',')
                 .append(n(o.rsrq)).append(',')
                 .append(n(o.rssi)).append(',')
                 .append(n(o.cqi)).append(',')
                 .append(n(o.bwKhz)).append(',')
                 .append(n(o.ageMs)).append(',')
                 .append(n(o.cc)).append(',')
                 .append(n(o.ccBwKhz)).append(',')
                 .append(o.ntn ? 1 : 0)
                 .append('\n');
            }
            r.rows = rows.size();
            r.where = write(ctx, "boresight-" + stamp() + ".csv", "text/csv", b.toString());
            r.ok = r.where != null;
            if (!r.ok) r.error = "could not open Downloads";
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
        }
        return r;
    }

    public static Result geojson(Context ctx, List<Store.Obs> rows, List<Store.Hit> hits) {
        Result r = new Result();
        try {
            StringBuilder b = new StringBuilder();
            b.append("{\"type\":\"FeatureCollection\",\"features\":[");
            boolean first = true;
            for (Store.Obs o : rows) {
                if (!o.located()) continue;
                if (!first) b.append(',');
                first = false;
                b.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\","
                       + "\"coordinates\":[").append(fmt(o.lon)).append(',')
                 .append(fmt(o.lat)).append("]},\"properties\":{");
                // Always emit one property first, so later ones can lead with a comma.
                b.append("\"kind\":\"").append(o.kind == null ? "cell" : o.kind).append('"');
                prop(b, "tech", o.tech, false);
                prop(b, "carrier", o.carrier, false);
                prop(b, "identity", o.ident, false);
                prop(b, "name", o.name, false);
                propNum(b, "dbm", o.dbm);
                propNum(b, "quality", o.qual);
                propNum(b, "timing_advance", o.ta);
                propNum(b, "arfcn", o.arfcn);
                propNum(b, "band", o.band);
                b.append(",\"serving\":").append(o.serving);
                b.append(",\"timestamp\":").append(o.ts);
                b.append("}}");
            }
            if (hits != null) {
                for (Store.Hit h : hits) {
                    if (Double.isNaN(h.lat)) continue;
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\","
                           + "\"coordinates\":[").append(fmt(h.lon)).append(',')
                     .append(fmt(h.lat)).append("]},\"properties\":{");
                    b.append("\"kind\":\"detection\"");
                    prop(b, "rule", h.rule, false);
                    prop(b, "vendor", h.vendor, false);
                    prop(b, "name", h.name, false);
                    prop(b, "mac", h.mac, false);
                    prop(b, "medium", h.medium, false);
                    b.append(",\"rule_confidence\":").append(h.confidence);
                    b.append(",\"timestamp\":").append(h.ts);
                    b.append("}}");
                }
            }
            b.append("]}");
            r.rows = rows.size() + (hits == null ? 0 : hits.size());
            r.where = write(ctx, "boresight-" + stamp() + ".geojson",
                    "application/geo+json", b.toString());
            r.ok = r.where != null;
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
        }
        return r;
    }

    /**
     * WiGLE's CSV shape. Cell rows use the identity in the SSID column and the PLMN plus
     * identity as the MAC-equivalent, which is what WiGLE's own importer does for cells.
     */
    public static Result wigle(Context ctx, List<Store.Obs> rows) {
        Result r = new Result();
        try {
            StringBuilder b = new StringBuilder();
            b.append("WigleWifi-1.4,appRelease=boresight,model=,release=,device=,"
                   + "display=,board=,brand=\n");
            b.append("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,"
                   + "CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n");
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            int n = 0;
            for (Store.Obs o : rows) {
                if (!o.located()) continue;
                String mac, ssid, auth, type;
                if (Store.KIND_WIFI.equals(o.kind)) {
                    mac = o.mac == null ? "" : o.mac;
                    ssid = o.name == null ? "" : o.name;
                    auth = o.extra == null ? "" : "[" + o.extra + "]";
                    type = "WIFI";
                } else if (Store.KIND_CELL.equals(o.kind)) {
                    mac = (o.mcc == null ? "" : o.mcc) + "_"
                        + (o.mnc == null ? "" : o.mnc) + "_"
                        + (o.ident == null ? "" : o.ident.replace(':', '_'));
                    ssid = (o.carrier == null ? "" : o.carrier);
                    auth = o.tech == null ? "" : o.tech;
                    type = "GSM";
                } else {
                    mac = o.mac == null ? "" : o.mac;
                    ssid = o.name == null ? "" : o.name;
                    auth = "";
                    type = "BLE";
                }
                b.append(q(mac)).append(',')
                 .append(q(ssid)).append(',')
                 .append(q(auth)).append(',')
                 .append(f.format(new Date(o.ts))).append(',')
                 .append(Sampler.has(o.arfcn) ? o.arfcn : 0).append(',')
                 .append(Sampler.has(o.dbm) ? o.dbm : 0).append(',')
                 .append(fmt(o.lat)).append(',')
                 .append(fmt(o.lon)).append(',')
                 .append(fmt(o.alt)).append(',')
                 .append(fmt(o.acc)).append(',')
                 .append(type).append('\n');
                n++;
            }
            r.rows = n;
            r.where = write(ctx, "boresight-wigle-" + stamp() + ".csv", "text/csv",
                    b.toString());
            r.ok = r.where != null;
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
        }
        return r;
    }

    /** The detector's rule table, so a signature list can be shared or kept. */
    public static Result rules(Context ctx, List<Detector.Rule> list) {
        Result r = new Result();
        try {
            StringBuilder b = new StringBuilder();
            b.append("label,vendor,kind,pattern,medium,confidence,enabled,provenance\n");
            for (Detector.Rule x : list) {
                b.append(q(x.label)).append(',').append(q(x.vendor)).append(',')
                 .append(q(x.kind)).append(',').append(q(x.pattern)).append(',')
                 .append(q(x.medium)).append(',').append(x.confidence).append(',')
                 .append(x.enabled ? 1 : 0).append(',').append(q(x.source)).append('\n');
            }
            r.rows = list.size();
            r.where = write(ctx, "boresight-rules-" + stamp() + ".csv", "text/csv",
                    b.toString());
            r.ok = r.where != null;
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
        }
        return r;
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Write into the public Downloads folder. MediaStore on Android 10 and up, a direct
     * file otherwise, and finally the app's own external directory if both are refused.
     */
    private static String write(Context ctx, String name, String mime, String content) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = cr.openOutputStream(uri);
                    if (os != null) {
                        Writer w = new OutputStreamWriter(os, "UTF-8");
                        try { w.write(content); } finally { w.close(); }
                        return "Downloads/" + name;
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, name);
                Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
                try { w.write(content); } finally { w.close(); }
                return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String q(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"";
    }

    private static String n(int v) {
        return Sampler.has(v) ? String.valueOf(v) : "";
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.7f", v);
    }

    private static void prop(StringBuilder b, String k, String v, boolean first) {
        if (v == null) return;
        if (!first) b.append(',');
        b.append('"').append(k).append("\":\"")
         .append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }

    private static void propNum(StringBuilder b, String k, int v) {
        if (!Sampler.has(v)) return;
        b.append(",\"").append(k).append("\":").append(v);
    }

    private Export() {}
}

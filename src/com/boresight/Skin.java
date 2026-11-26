package com.boresight;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads TMOG's own .tmogskin format, so the skins already on the desktop drive this app
 * unchanged. A skin is either a single .tmogskin JSON file or a .tmogskin directory
 * holding skin.json plus artwork.
 *
 * Only the keys that mean something on a phone are honoured; anything unknown is ignored
 * rather than treated as an error, which is what lets a desktop skin load as-is.
 *
 * Channel names come from TMOG (cpu, gpu, memory, thermal, disk, network, power, alert).
 * The radio metrics are mapped onto them in CHANNEL below, so any skin colours this app
 * sensibly without knowing anything about cellular.
 */
public final class Skin {

    // Radio metric -> TMOG channel, so existing skins colour us correctly.
    public static final String CH_RSRP  = "cpu";
    public static final String CH_SINR  = "gpu";
    public static final String CH_RSRQ  = "memory";
    public static final String CH_NEIGH = "network";
    public static final String CH_FREQ  = "disk";
    public static final String CH_SAT   = "thermal";
    public static final String CH_ALERT = "alert";
    public static final String CH_POWER = "power";

    public String id = "builtin.instrument";
    public String name = "Instrument";
    public String author = "";
    public String description = "";
    public String sourcePath = "";
    public boolean builtin = true;

    // metrics
    public float cornerRadius = 10, tileCornerRadius = 7, badgeCornerRadius = 5;
    public float borderWidth = 1, tileStrokeWidth = 1;
    public float padding = 10, headerBottomPadding = 6, headerGap = 3, sectionSpacing = 8;

    // surfaces: either a flat colour or a two-stop vertical gradient
    public int[] window = {0xFF101418, 0xFF05070A};
    public int windowStroke = 0xFF43505C;
    public int[] header = {0xFF141A20, 0xFF080C10};
    public int headerRule = 0x9943505C;
    public int[] tile = {0xE6070A0D, 0xE6070A0D};
    public int tileStroke = 0x14FFFFFF;
    public int[] tileGloss = null;
    public int badgeStroke = 0xAA4FD1C5;

    // ink
    public int inkTitle = 0xFFE6EDF3, inkPrimary = 0xFFDCE6EF, inkSecondary = 0xFF7D8B99,
            inkControl = 0xFFC9D6E2, inkBadge = 0xFF4FD1C5, inkGrip = 0xFF7D8B99,
            inkRule = 0x14FFFFFF;

    // typography
    public float szTitle = 15, szHeading = 11, szLabel = 10, szValue = 13,
            szRow = 12, szRowValue = 12, szAux = 10;
    public float titleTracking = 0.04f;
    public boolean monoTitle, monoLabel, monoValue = true, monoRow;
    public int wTitle = Typeface.BOLD, wHeading = Typeface.BOLD, wLabel = Typeface.BOLD,
            wValue = Typeface.BOLD, wRow = Typeface.NORMAL;

    // meters
    /** vertical | graph | rows | flat */
    public String meterStyle = "graph";
    public boolean positionalPalette, flatMeters, showsGrid = true, auxiliary;
    public int segments = 8, split = 1, meterColumns = 2;
    public int meterGround = 0xFF000000;
    public float graphHeight = 56;
    public float insetTop = 6, insetBottom = 4, insetLead = 6, insetTrail = 6;

    public final java.util.Map<String, int[]> channels = new java.util.HashMap<>();

    public List<String> quadrants = new ArrayList<>(
            java.util.Arrays.asList(CH_RSRP, CH_SINR, CH_RSRQ, CH_NEIGH));

    // ---------------------------------------------------------------- defaults

    public Skin() {
        // channel -> { accent, lit, unlit }
        channels.put("cpu",     new int[]{0xFF4FD1C5, 0xFF4FD1C5, 0x334FD1C5});
        channels.put("gpu",     new int[]{0xFF7FB2F0, 0xFF7FB2F0, 0x337FB2F0});
        channels.put("memory",  new int[]{0xFFD48CFF, 0xFFD48CFF, 0x33D48CFF});
        channels.put("thermal", new int[]{0xFFFFB454, 0xFFFFB454, 0x33FFB454});
        channels.put("disk",    new int[]{0xFF7BE08A, 0xFF7BE08A, 0x337BE08A});
        channels.put("network", new int[]{0xFF7FB2F0, 0xFF7FB2F0, 0x337FB2F0});
        channels.put("power",   new int[]{0xFFFFD166, 0xFFFFD166, 0x33FFD166});
        channels.put("alert",   new int[]{0xFFFF7B72, 0xFFFF7B72, 0x33FF7B72});
    }

    public int accent(String channel) {
        int[] c = channels.get(channel);
        return c != null ? c[0] : inkBadge;
    }

    public int lit(String channel) {
        int[] c = channels.get(channel);
        return c != null && c.length > 1 ? c[1] : accent(channel);
    }

    public int unlit(String channel) {
        int[] c = channels.get(channel);
        return c != null && c.length > 2 ? c[2] : alpha(accent(channel), 0.22f);
    }

    public boolean darkish() {
        return luma(window[0]) < 0.5f;
    }

    public int windowFlat() { return window[0]; }
    public int tileFlat()   { return tile[0]; }

    // ---------------------------------------------------------------- loading

    /** Every skin the app can offer: the two built-ins plus whatever the user has added. */
    public static List<Skin> available(Context c) {
        List<Skin> out = new ArrayList<>();
        out.add(new Skin());                       // Instrument (built in)
        out.add(classicTaskManager());             // Task Manager green-on-black

        for (File dir : skinDirs(c)) {
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                if (!f.getName().toLowerCase().endsWith(".tmogskin")) continue;
                Skin s = load(f);
                if (s != null) out.add(s);
            }
        }
        return out;
    }

    /** Where a user may drop skins. Both are reachable without any special permission. */
    public static List<File> skinDirs(Context c) {
        List<File> out = new ArrayList<>();
        out.add(new File(c.getFilesDir(), "skins"));
        File ext = c.getExternalFilesDir(null);
        if (ext != null) out.add(new File(ext, "skins"));
        return out;
    }

    public static Skin load(File path) {
        try {
            File json = path;
            if (path.isDirectory()) {
                json = new File(path, "skin.json");
                if (!json.isFile()) return null;
            }
            String text = slurp(new FileInputStream(json));
            Skin s = parse(new JSONObject(text));
            s.builtin = false;
            s.sourcePath = path.getAbsolutePath();
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public static Skin byId(Context c, String id) {
        for (Skin s : available(c)) if (s.id.equals(id)) return s;
        return new Skin();
    }

    public static Skin current(Context c) {
        return byId(c, Prefs.string(c, "skin_id", "builtin.instrument"));
    }

    // ---------------------------------------------------------------- parsing

    public static Skin parse(JSONObject o) {
        Skin s = new Skin();
        s.id = o.optString("id", "skin." + Math.abs(o.hashCode()));
        s.name = o.optString("name", s.id);
        s.author = o.optString("author", "");
        s.description = o.optString("description", "");

        JSONObject m = o.optJSONObject("metrics");
        if (m != null) {
            s.cornerRadius        = (float) m.optDouble("cornerRadius", s.cornerRadius);
            s.tileCornerRadius    = (float) m.optDouble("tileCornerRadius", s.tileCornerRadius);
            s.badgeCornerRadius   = (float) m.optDouble("badgeCornerRadius", s.badgeCornerRadius);
            s.borderWidth         = (float) m.optDouble("borderWidth", s.borderWidth);
            s.tileStrokeWidth     = (float) m.optDouble("tileStrokeWidth", s.tileStrokeWidth);
            s.padding             = (float) m.optDouble("padding", s.padding);
            s.headerBottomPadding = (float) m.optDouble("headerBottomPadding", s.headerBottomPadding);
            s.headerGap           = (float) m.optDouble("headerGap", s.headerGap);
            s.sectionSpacing      = (float) m.optDouble("sectionSpacing", s.sectionSpacing);
        }

        JSONObject su = o.optJSONObject("surfaces");
        if (su != null) {
            s.window      = stops(su, "window", s.window);
            s.header      = stops(su, "header", s.header);
            s.tile        = stops(su, "tile", s.tile);
            s.tileGloss   = su.has("tileGloss") ? stops(su, "tileGloss", null) : null;
            s.windowStroke = colour(su.optString("windowStroke", null), s.windowStroke);
            s.headerRule   = colour(su.optString("headerRule", null), s.headerRule);
            s.tileStroke   = colour(su.optString("tileStroke", null), s.tileStroke);
            s.badgeStroke  = colour(su.optString("badgeStroke", null), s.badgeStroke);
        }

        JSONObject ink = o.optJSONObject("ink");
        if (ink != null) {
            s.inkTitle     = colour(ink.optString("title", null), s.inkTitle);
            s.inkPrimary   = colour(ink.optString("primary", null), s.inkPrimary);
            s.inkSecondary = colour(ink.optString("secondary", null), s.inkSecondary);
            s.inkControl   = colour(ink.optString("control", null), s.inkControl);
            s.inkBadge     = colour(ink.optString("badge", null), s.inkBadge);
            s.inkGrip      = colour(ink.optString("grip", null), s.inkGrip);
            s.inkRule      = colour(ink.optString("rule", null), s.inkRule);
        }

        JSONObject ty = o.optJSONObject("typography");
        if (ty != null) {
            s.titleTracking = (float) ty.optDouble("titleTracking", 0) / 25f;
            Font f;
            f = font(ty, "title");    if (f != null) { s.szTitle = f.size;    s.wTitle = f.weight;  s.monoTitle = f.mono; }
            f = font(ty, "heading");  if (f != null) { s.szHeading = f.size;  s.wHeading = f.weight; }
            f = font(ty, "label");    if (f != null) { s.szLabel = f.size;    s.wLabel = f.weight;  s.monoLabel = f.mono; }
            f = font(ty, "value");    if (f != null) { s.szValue = f.size;    s.wValue = f.weight;  s.monoValue = f.mono; }
            f = font(ty, "row");      if (f != null) { s.szRow = f.size;      s.wRow = f.weight;    s.monoRow = f.mono; }
            f = font(ty, "rowValue"); if (f != null) { s.szRowValue = f.size; }
            f = font(ty, "auxLine");  if (f != null) { s.szAux = f.size; }
        }

        JSONArray q = o.optJSONArray("quadrants");
        if (q != null && q.length() > 0) {
            s.quadrants = new ArrayList<>();
            for (int i = 0; i < q.length(); i++) s.quadrants.add(q.optString(i));
        }

        JSONObject me = o.optJSONObject("meters");
        if (me != null) {
            s.meterStyle        = me.optString("style", s.meterStyle);
            s.positionalPalette = me.optBoolean("positionalPalette", s.positionalPalette);
            s.flatMeters        = me.optBoolean("flat", s.flatMeters);
            s.auxiliary         = me.optBoolean("auxiliary", s.auxiliary);
            s.segments          = me.optInt("segments", s.segments);
            s.split             = Math.max(1, me.optInt("split", s.split));
            s.meterColumns      = Math.max(1, me.optInt("columns", s.meterColumns));
            s.meterGround       = colour(me.optString("ground", null), s.meterGround);

            JSONObject gi = me.optJSONObject("inset");
            if (gi != null) {
                s.insetTop    = (float) gi.optDouble("top", s.insetTop);
                s.insetBottom = (float) gi.optDouble("bottom", s.insetBottom);
                s.insetLead   = (float) gi.optDouble("leading", s.insetLead);
                s.insetTrail  = (float) gi.optDouble("trailing", s.insetTrail);
            }
            JSONObject g = me.optJSONObject("graph");
            if (g != null) {
                s.showsGrid   = g.optBoolean("showsGrid", s.showsGrid);
                s.graphHeight = (float) g.optDouble("height", s.graphHeight);
            }
            JSONObject ch = me.optJSONObject("channels");
            if (ch != null) {
                java.util.Iterator<String> it = ch.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject c = ch.optJSONObject(k);
                    if (c == null) continue;
                    int[] prev = s.channels.get(k);
                    int acc = colour(c.optString("accent", null),
                            prev != null ? prev[0] : s.inkBadge);
                    int lit = colour(c.optString("lit", null), acc);
                    int unl = colour(c.optString("unlit", null), alpha(acc, 0.22f));
                    s.channels.put(k, new int[]{acc, lit, unl});
                }
            }
        }
        return s;
    }

    private static class Font { float size; int weight; boolean mono; }

    private static Font font(JSONObject ty, String key) {
        JSONObject f = ty.optJSONObject(key);
        if (f == null) return null;
        Font out = new Font();
        // Desktop point sizes read small on a phone; lift them onto a usable scale.
        out.size = (float) f.optDouble("size", 11) * 1.15f + 1f;
        String w = f.optString("weight", "regular");
        out.weight = ("bold".equals(w) || "semibold".equals(w) || "heavy".equals(w) || "black".equals(w))
                ? Typeface.BOLD : Typeface.NORMAL;
        out.mono = "monospaced".equals(f.optString("design", ""));
        return out;
    }

    /** A surface is either "#RRGGBBAA" or {"gradient":["#..","#.."]}. */
    private static int[] stops(JSONObject o, String key, int[] def) {
        Object v = o.opt(key);
        if (v instanceof String) {
            int c = colour((String) v, def == null ? 0xFF000000 : def[0]);
            return new int[]{c, c};
        }
        if (v instanceof JSONObject) {
            JSONArray g = ((JSONObject) v).optJSONArray("gradient");
            if (g != null && g.length() >= 2) {
                return new int[]{ colour(g.optString(0), 0xFF000000),
                                  colour(g.optString(1), 0xFF000000) };
            }
            if (g != null && g.length() == 1) {
                int c = colour(g.optString(0), 0xFF000000);
                return new int[]{c, c};
            }
        }
        return def;
    }

    /**
     * TMOG writes #RGB, #RRGGBB or #RRGGBBAA. Android wants alpha first, so the
     * eight-digit form has to be rotated rather than handed straight over.
     */
    public static int colour(String s, int def) {
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty() || "null".equals(s)) return def;
        if (s.charAt(0) != '#') s = "#" + s;
        try {
            String h = s.substring(1);
            if (h.length() == 3) {
                StringBuilder b = new StringBuilder("#");
                for (int i = 0; i < 3; i++) { b.append(h.charAt(i)).append(h.charAt(i)); }
                return Color.parseColor(b.toString());
            }
            if (h.length() == 6) return Color.parseColor("#" + h);
            if (h.length() == 8) {
                String rgb = h.substring(0, 6), a = h.substring(6, 8);
                return Color.parseColor("#" + a + rgb);
            }
        } catch (Exception ignored) {}
        return def;
    }

    public static int alpha(int c, float a) {
        return (c & 0x00FFFFFF) | (((int) (255 * Math.max(0, Math.min(1, a)))) << 24);
    }

    public static float luma(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /** Mix toward another colour, for hover and pressed states. */
    public static int mix(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a)   + (Color.red(b)   - Color.red(a))   * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a)  + (Color.blue(b)  - Color.blue(a))  * t));
    }

    // ---------------------------------------------------------------- drawables

    private static GradientDrawable grad(int[] stops, float radiusPx,
                                         float strokePx, int strokeColour) {
        GradientDrawable g = (stops[0] == stops[1])
                ? new GradientDrawable()
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, stops);
        if (stops[0] == stops[1]) g.setColor(stops[0]);
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radiusPx);
        if (strokePx >= 1) g.setStroke((int) strokePx, strokeColour);
        return g;
    }

    public Drawable windowBg(Theme t) {
        return grad(window, t.dpf(cornerRadius), 0, 0);
    }

    public Drawable headerBg(Theme t) {
        return grad(header, 0, 0, 0);
    }

    public Drawable tileBg(Theme t) {
        return grad(tile, t.dpf(tileCornerRadius),
                Math.max(0, t.dpf(tileStrokeWidth)), tileStroke);
    }

    public Drawable badgeBg(Theme t, int fill) {
        int[] st = {fill, fill};
        return grad(st, t.dpf(badgeCornerRadius), t.dpf(1), badgeStroke);
    }

    public Typeface face(boolean mono, int weight) {
        return mono ? Typeface.create(Typeface.MONOSPACE, weight)
                    : Typeface.create(Typeface.SANS_SERIF, weight);
    }

    // ---------------------------------------------------------------- built-ins

    /** The classic: navy chrome, etched grey boxes, green LEDs on black. */
    public static Skin classicTaskManager() {
        Skin s = new Skin();
        s.id = "builtin.taskman";
        s.name = "Task Manager";
        s.author = "built in";
        s.description = "Grey chrome, etched boxes, green-on-black meters.";
        s.cornerRadius = 0; s.tileCornerRadius = 0; s.badgeCornerRadius = 0;
        s.borderWidth = 1; s.tileStrokeWidth = 1;
        s.window = new int[]{0xFFD4D0C8, 0xFFD4D0C8};
        s.windowStroke = 0xFF000000;
        s.header = new int[]{0xFF000080, 0xFF000080};
        s.headerRule = 0xFFFFFFFF;
        s.tile = new int[]{0xFFD4D0C8, 0xFFD4D0C8};
        s.tileStroke = 0xFF808080;
        s.badgeStroke = 0xFF808080;
        s.inkTitle = 0xFFFFFFFF;
        s.inkPrimary = 0xFF000000;
        s.inkSecondary = 0xFF404040;
        s.inkControl = 0xFF000000;
        s.inkBadge = 0xFF000080;
        s.inkGrip = 0xFF808080;
        s.inkRule = 0xFF808080;
        s.meterStyle = "vertical";
        s.flatMeters = true;
        s.segments = 8;
        s.split = 2;
        s.meterGround = 0xFF000000;
        s.insetTop = 8; s.insetBottom = 3; s.insetLead = 10; s.insetTrail = 10;
        int[] green = {0xFF008000, 0xFF00FF00, 0xFF006000};
        for (String k : new String[]{"cpu", "memory", "gpu", "thermal", "disk", "network"}) {
            s.channels.put(k, green);
        }
        s.channels.put("power", new int[]{0xFF000000, 0xFF00FF00, 0xFF006000});
        s.channels.put("alert", new int[]{0xFF800000, 0xFFFF0000, 0xFF600000});
        s.quadrants = new ArrayList<>(java.util.Arrays.asList(CH_RSRP, CH_SINR, CH_RSRQ, CH_NEIGH));
        return s;
    }

    // ---------------------------------------------------------------- install

    /** Copy a .tmogskin the user picked into the app's own skins folder. */
    public static File installDir(Context c) {
        File d = new File(c.getFilesDir(), "skins");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static String slurp(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new String(o.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }

    public static String readAll(InputStream in) throws Exception { return slurp(in); }
}

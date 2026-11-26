package com.boresight;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One visual vocabulary for every screen.
 *
 * The look is a desktop instrument panel rather than a phone app: flat surfaces with
 * hairline borders, dense rows, monospace numerics, and plots that scroll against a
 * fixed grid. Everything is built in code, so there is no theme XML to drift.
 */
public final class Theme {

    public final boolean dark;

    /** Page and surfaces. */
    public final int bg, panel, panelHi, plot, line, lineSoft;
    /** Text. */
    public final int ink, inkDim, inkFaint, inkNum;
    /** Meaning. */
    public final int accent, accentDim, good, warn, bad, info, violet, cyan, scrim;

    private final float density, scaled;

    /** Plot series colours, in assignment order, for graph grids. */
    public final int[] series;

    public Theme(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        density = c.getResources().getDisplayMetrics().density;
        scaled  = c.getResources().getDisplayMetrics().scaledDensity;

        if (dark) {
            bg       = 0xFF0C0F13;
            panel    = 0xFF13171D;
            panelHi  = 0xFF1A202A;
            plot     = 0xFF0A0D11;
            line     = 0xFF2A313C;
            lineSoft = 0xFF1C222B;
            ink      = 0xFFE6EAF2;
            inkDim   = 0xFF9AA4B4;
            inkFaint = 0xFF5C6676;
            inkNum   = 0xFFF2F5FA;
            accent   = 0xFF4FD1C5;
            accentDim= 0x264FD1C5;
            good     = 0xFF5CD98B;
            warn     = 0xFFE8C06B;
            bad      = 0xFFE8796B;
            info     = 0xFF6FA8F0;
            violet   = 0xFFB08BE8;
            cyan     = 0xFF4FD1C5;
        } else {
            bg       = 0xFFEDEFF3;
            panel    = 0xFFFFFFFF;
            panelHi  = 0xFFF5F7FA;
            plot     = 0xFFFBFCFD;
            line     = 0xFFC9CFD8;
            lineSoft = 0xFFE3E7ED;
            ink      = 0xFF11151B;
            inkDim   = 0xFF56606E;
            inkFaint = 0xFF8A93A1;
            inkNum   = 0xFF0B0E13;
            accent   = 0xFF0E7C73;
            accentDim= 0x1A0E7C73;
            good     = 0xFF1C7A3F;
            warn     = 0xFF8A5F08;
            bad      = 0xFFB02F26;
            info     = 0xFF14579E;
            violet   = 0xFF6B3FB0;
            cyan     = 0xFF0E7C73;
        }
        scrim = 0xAA000000;

        series = new int[]{ accent, info, violet, warn, good, bad,
                dark ? 0xFF8FD0C0 : 0xFF2A6E63,
                dark ? 0xFFE0A0C8 : 0xFF8E3A6B };
    }

    // ------------------------------------------------------------------ metrics

    public int dp(int v)      { return (int) (v * density + 0.5f); }
    public float dpf(float v) { return v * density; }
    public float sp(float v)  { return v * scaled; }
    public int hair()         { return Math.max(1, (int) (density * 0.6f)); }

    // ------------------------------------------------------------------ surfaces

    /** Flat panel with a hairline border. The default container on every screen. */
    public GradientDrawable bevel(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dpf(3));
        g.setColor(fill);
        g.setStroke(hair(), line);
        return g;
    }

    public GradientDrawable solid(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dpf(radiusDp));
        g.setColor(fill);
        return g;
    }

    public RippleDrawable ripple(android.graphics.drawable.Drawable base) {
        return new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x30FFFFFF : 0x1F000000), base, null);
    }

    /** A framed block of content. */
    public LinearLayout panel(Context c) {
        LinearLayout l = column(c);
        l.setBackground(bevel(panel));
        int p = dp(10);
        l.setPadding(p, dp(8), p, dp(9));
        return l;
    }

    public void framed(View v) { v.setBackground(bevel(panel)); }

    public void tappable(View v) {
        v.setBackground(ripple(bevel(panel)));
        v.setClickable(true);
    }

    /** Horizontal hairline, for separating dense rows. */
    public View rule(Context c) {
        View v = new View(c);
        v.setBackgroundColor(lineSoft);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, hair());
        v.setLayoutParams(lp);
        return v;
    }

    // ------------------------------------------------------------------ text

    public TextView text(Context c, String s, float sizeSp, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(colour);
        return t;
    }

    /** Numbers always get the monospace face, so columns line up like an instrument. */
    public TextView num(Context c, String s, float sizeSp, int colour) {
        TextView t = text(c, s, sizeSp, colour);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    public TextView bold(Context c, String s, float sizeSp, int colour) {
        TextView t = text(c, s, sizeSp, colour);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Panel caption: small, upper, letter-spaced, the way a desktop utility titles a pane. */
    public TextView caption(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.US), 10, inkDim);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public TextView heading(Context c, String s) {
        TextView t = caption(c, s);
        t.setTextColor(accent);
        t.setPadding(dp(2), dp(14), dp(2), dp(6));
        return t;
    }

    // ------------------------------------------------------------------ colour

    public int grade(int dbm) {
        if (!Sampler.has(dbm) || dbm == 0) return inkDim;
        if (dbm >= -85)  return good;
        if (dbm >= -100) return dark ? 0xFF9BD96B : 0xFF4A7A12;
        if (dbm >= -110) return warn;
        return bad;
    }

    public int techColour(String tech) {
        if (tech == null) return inkDim;
        switch (tech) {
            case "NR":    return violet;
            case "LTE":   return info;
            case "WCDMA": return warn;
            case "GSM":   return inkDim;
            default:      return accent;
        }
    }

    public static int alpha(int colour, float a) {
        return (colour & 0x00FFFFFF) | (((int) (255 * Math.max(0, Math.min(1, a)))) << 24);
    }

    /** Stable readable colour for an arbitrary label. */
    public int hue(String seed) {
        if (seed == null || seed.isEmpty()) return inkDim;
        int h = Math.abs(seed.hashCode());
        return series[h % series.length];
    }

    // ------------------------------------------------------------------ layout sugar

    public LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    /**
     * A count with its noun, pluralised.
     *
     * "1 detections" is the kind of thing that makes a tool look unfinished, and in this
     * survey the count of detections is usually exactly one.
     */
    public static String count(long n, String singular) {
        return n + " " + (n == 1 ? singular : singular + "s");
    }

    /** For nouns whose plural is not just an s. */
    public static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout.LayoutParams fixed(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp,
                                             int l, int t, int r, int b) {
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    /**
     * A label-over-value readout, the dense pair a desktop monitor uses everywhere.
     */
    public LinearLayout stat(Context c, String label, String value, int valueColour,
                             float valueSp) {
        LinearLayout box = column(c);
        box.addView(caption(c, label));
        TextView v = num(c, value, valueSp, valueColour);
        v.setPadding(0, dp(1), 0, 0);
        box.addView(v);
        return box;
    }

    /**
     * Inset the content past the system bars and the action bar.
     *
     * Targeting SDK 35 opts the window into edge-to-edge on Android 15 and up: the layout
     * starts at y=0, behind the status bar, with the action bar drawn over the top of it.
     * Without this the first row of every screen is simply invisible.
     */
    /**
     * Colour the system bars to match the title bar and pick the right icon polarity.
     * Layout insets are handled by Header.inset, because the bar has to run underneath
     * the status bar while the page does not.
     */
    public void systemBars(android.app.Activity a) {
        a.getWindow().setStatusBarColor(header0());
        a.getWindow().setNavigationBarColor(windowFlat());
        View dec = a.getWindow().getDecorView();
        int f = dec.getSystemUiVisibility();
        boolean lightBar = Skin.luma(header0()) > 0.55f;
        boolean lightNav = Skin.luma(windowFlat()) > 0.55f;
        if (lightBar) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else          f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (lightNav) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else          f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        dec.setSystemUiVisibility(f);
    }

    private int header0() {
        Skin s = skinRef;
        return s != null ? s.header[0] : panel;
    }

    private int windowFlat() {
        Skin s = skinRef;
        return s != null ? s.windowFlat() : bg;
    }

    /** The skin in force, so the system bars can match it. */
    private Skin skinRef;

    public void useSkin(Skin s) { skinRef = s; }
}
package com.boresight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Scrolling history plot: fixed grid, line scrolling right to left, filled area beneath.
 * The shape TMOG's Traces skin draws, and the shape a desktop monitor has always drawn.
 *
 * Holds its own ring buffer so any metric can be dropped in without a backing store.
 */
public class Graph extends View {

    private final Theme th;
    private Skin skin;
    private String channel = Skin.CH_RSRP;

    private final int cap;
    private final float[] buf;
    private final long[] when;
    private int count, head;

    private float lo = -135, hi = -60;
    private boolean autoScale;
    private float gridStep = 25;
    private String unit = "dBm";
    private String caption = "";
    private boolean showLabels = true, showGrid = true, showValue = true, fill = true;
    private int forced = 0;                 // non-zero overrides the channel colour
    private String emptyNote = "collecting";
    /** Time-axis compression. 0 is linear; higher packs more history into the left. */
    private float warp = 24f;
    /**
     * Display smoothing, 0 to 1. Damps the jitter the modem reports between identical
     * readings. The numeric readouts elsewhere always show the raw value; only the
     * drawn line is smoothed, so nothing here invents a measurement.
     */
    private float smoothing = 0.45f;
    private float ema = Float.NaN;

    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path(), area = new Path();

    public Graph(Context c, Theme theme, Skin s) { this(c, theme, s, 240); }

    public Graph(Context c, Theme theme, Skin s, int capacity) {
        super(c);
        th = theme;
        skin = s;
        cap = Math.max(8, capacity);
        buf = new float[cap];
        when = new long[cap];

        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setStrokeWidth(Math.max(1, th.dpf(0.6f)));
        pGrid.setPathEffect(new DashPathEffect(new float[]{th.dpf(2), th.dpf(3)}, 0));

        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(th.dpf(1.6f));
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pLine.setStrokeCap(Paint.Cap.ROUND);

        pFill.setStyle(Paint.Style.FILL);
        pText.setTypeface(Typeface.MONOSPACE);
        pText.setTextSize(th.sp(9));
    }

    // ------------------------------------------------------------------ config

    public Graph channel(String ch)   { channel = ch; invalidate(); return this; }
    public Graph colour(int c)        { forced = c; invalidate(); return this; }
    public Graph range(float l, float h) { lo = l; hi = h; autoScale = false; return this; }
    public Graph autoScale(boolean b) { autoScale = b; return this; }
    public Graph grid(float step)     { gridStep = step; return this; }
    public Graph unit(String u)       { unit = u; return this; }
    public Graph caption(String s)    { caption = s; invalidate(); return this; }
    public Graph labels(boolean b)    { showLabels = b; return this; }
    public Graph gridlines(boolean b) { showGrid = b; return this; }
    public Graph value(boolean b)     { showValue = b; return this; }
    public Graph filled(boolean b)    { fill = b; return this; }
    public Graph note(String s)       { emptyNote = s; return this; }
    public Graph warp(float k)        { warp = k; invalidate(); return this; }
    public Graph smooth(float a)      { smoothing = Math.max(0f, Math.min(0.95f, a)); return this; }

    public void reskin(Skin s) { skin = s; invalidate(); }

    // ------------------------------------------------------------------ data

    public void push(float v) {
        if (Float.isNaN(v)) return;
        if (smoothing > 0f) {
            ema = Float.isNaN(ema) ? v : ema + (v - ema) * (1f - smoothing);
            v = ema;
        }
        buf[head] = v;
        when[head] = System.currentTimeMillis();
        head = (head + 1) % cap;
        if (count < cap) count++;
        invalidate();
    }

    /** Record a gap, so a dropout shows as a break rather than a straight line across. */
    public void pushGap() {
        ema = Float.NaN;
        buf[head] = Float.NaN;
        when[head] = System.currentTimeMillis();
        head = (head + 1) % cap;
        if (count < cap) count++;
        invalidate();
    }

    public void clear() { count = 0; head = 0; ema = Float.NaN; invalidate(); }

    public int size() { return count; }

    public float last() {
        for (int i = 0; i < count; i++) {
            float v = buf[(head - 1 - i + cap * 2) % cap];
            if (!Float.isNaN(v)) return v;
        }
        return Float.NaN;
    }

    /** Replace the whole window at once, oldest first. */
    public void setSeries(float[] values) {
        count = 0; head = 0;
        if (values == null) { invalidate(); return; }
        int start = Math.max(0, values.length - cap);
        for (int i = start; i < values.length; i++) {
            buf[head] = values[i];
            when[head] = 0;
            head = (head + 1) % cap;
            if (count < cap) count++;
        }
        invalidate();
    }

    private float[] window() {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) out[i] = buf[(head - count + i + cap * 2) % cap];
        return out;
    }

    /**
     * Horizontal position for a sample, by how many samples old it is.
     *
     * The time axis is compressed toward the left the way a desktop monitor draws it:
     * the newest samples get most of the width and move visibly, while older ones pack
     * into the left edge so a long history still fits. A logarithm does this smoothly —
     * with warp at 24 the newest tenth of the buffer occupies about a third of the plot.
     * Setting warp to zero restores a plain linear axis.
     */
    private float xFor(int ageSamples, float padL, float gw) {
        float u = ageSamples / (float) Math.max(1, cap - 1);
        if (u < 0) u = 0; else if (u > 1) u = 1;
        if (warp <= 0) return padL + gw * (1f - u);
        float f = (float) (Math.log1p(warp * u) / Math.log1p(warp));
        return padL + gw * (1f - f);
    }

    private float frac(float v) {
        float f = (v - lo) / (hi - lo);
        return Math.max(0f, Math.min(1f, f));
    }

    // ------------------------------------------------------------------ draw

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        int accent = forced != 0 ? forced : skin.accent(channel);
        float[] v = window();

        if (autoScale) {
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (float x : v) {
                if (Float.isNaN(x)) continue;
                mn = Math.min(mn, x); mx = Math.max(mx, x);
            }
            if (mn <= mx) {
                float pad = Math.max(1f, (mx - mn) * 0.2f);
                lo = mn - pad; hi = mx + pad;
            }
        }

        pText.setTextSize(th.sp(9));
        float padL = showLabels ? pText.measureText("-135") + th.dpf(5) : th.dpf(2);
        float padR = th.dpf(3), padT = th.dpf(3), padB = th.dpf(3);
        float gw = w - padL - padR, gh = h - padT - padB;
        if (gw <= 1 || gh <= 1) return;

        // Grid. Fixed to the value axis, so the line scrolls against a still backdrop.
        if (showGrid && skin.showsGrid) {
            pGrid.setColor(skin.inkRule);
            float startVal = (float) (Math.ceil(lo / gridStep) * gridStep);
            for (float d = startVal; d <= hi; d += gridStep) {
                float y = padT + gh * (1f - frac(d));
                cv.drawLine(padL, y, padL + gw, y, pGrid);
                if (showLabels) {
                    pText.setColor(skin.inkSecondary);
                    cv.drawText(fmt(d), th.dpf(1), y + pText.getTextSize() * 0.35f, pText);
                }
            }
        }

        if (caption != null && !caption.isEmpty()) {
            pText.setColor(skin.inkSecondary);
            pText.setTypeface(skin.face(skin.monoLabel, Typeface.BOLD));
            pText.setTextSize(th.sp(skin.szLabel * 0.78f));
            cv.drawText(caption.toUpperCase(java.util.Locale.US),
                    padL + th.dpf(3), padT + pText.getTextSize() + th.dpf(1), pText);
            pText.setTypeface(Typeface.MONOSPACE);
        }

        int real = 0;
        for (float x : v) if (!Float.isNaN(x)) real++;
        if (real < 2) {
            pText.setColor(skin.inkSecondary);
            pText.setTextSize(th.sp(10));
            cv.drawText(emptyNote, padL + th.dpf(4), padT + gh / 2, pText);
            return;
        }

        float headX = padL + gw, headY = Float.NaN;

        // Fill under the trace, fading downward the way a monitor shades its history.
        if (fill) {
            pFill.setShader(new LinearGradient(0, padT, 0, padT + gh,
                    Skin.alpha(accent, 0.30f), Skin.alpha(accent, 0.02f), Shader.TileMode.CLAMP));
        }

        line.reset(); area.reset();
        float baseY = padT + gh;

        // Walk the window as runs of real samples separated by dropouts, and draw each
        // run as one smooth curve. Quadratic segments through the midpoints of the
        // polyline give a continuous line with no overshoot, which matters here: an
        // interpolation that overshoots would draw signal levels that were never measured.
        int i = 0;
        while (i < v.length) {
            while (i < v.length && Float.isNaN(v[i])) i++;
            int start = i;
            while (i < v.length && !Float.isNaN(v[i])) i++;
            int end = i;                                  // exclusive
            int n = end - start;
            if (n == 0) continue;

            float[] xs = new float[n], ys = new float[n];
            for (int k = 0; k < n; k++) {
                xs[k] = xFor(v.length - 1 - (start + k), padL, gw);
                ys[k] = padT + gh * (1f - frac(v[start + k]));
            }
            if (end == v.length) { headX = xs[n - 1]; headY = ys[n - 1]; }

            line.moveTo(xs[0], ys[0]);
            if (fill) { area.moveTo(xs[0], baseY); area.lineTo(xs[0], ys[0]); }

            if (n == 1 || smoothing <= 0f) {
                for (int k = 1; k < n; k++) {
                    line.lineTo(xs[k], ys[k]);
                    if (fill) area.lineTo(xs[k], ys[k]);
                }
            } else {
                for (int k = 1; k < n; k++) {
                    float mx = (xs[k - 1] + xs[k]) / 2f, my = (ys[k - 1] + ys[k]) / 2f;
                    line.quadTo(xs[k - 1], ys[k - 1], mx, my);
                    if (fill) area.quadTo(xs[k - 1], ys[k - 1], mx, my);
                }
                line.lineTo(xs[n - 1], ys[n - 1]);
                if (fill) area.lineTo(xs[n - 1], ys[n - 1]);
            }
            if (fill) { area.lineTo(xs[n - 1], baseY); area.close(); }
        }

        if (fill) cv.drawPath(area, pFill);
        pLine.setColor(accent);
        cv.drawPath(line, pLine);

        if (!Float.isNaN(headY)) {
            pDot.setColor(accent);
            cv.drawCircle(headX, headY, th.dpf(2.2f), pDot);
        }

        if (showValue) {
            float lastV = last();
            if (!Float.isNaN(lastV)) {
                String s = fmt(lastV) + (unit.isEmpty() ? "" : " " + unit);
                pText.setColor(accent);
                pText.setTypeface(skin.face(true, Typeface.BOLD));
                pText.setTextSize(th.sp(skin.szValue * 0.86f));
                float tw = pText.measureText(s);
                cv.drawText(s, padL + gw - tw - th.dpf(2),
                        padT + pText.getTextSize() + th.dpf(1), pText);
                pText.setTypeface(Typeface.MONOSPACE);
            }
        }
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    // ==================================================================
    //  Segmented LED meter: the classic Task Manager bar.
    // ==================================================================

    public static class Led extends View {
        private final Theme th;
        private Skin skin;
        private String channel;
        private float value, lo = -135, hi = -60;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean vertical = true;

        public Led(Context c, Theme theme, Skin s, String ch) {
            super(c);
            th = theme; skin = s; channel = ch;
            value = Float.NaN;
        }

        public Led range(float l, float h) { lo = l; hi = h; return this; }
        public Led horizontal() { vertical = false; return this; }
        public void reskin(Skin s) { skin = s; invalidate(); }
        public void setChannel(String ch) { channel = ch; invalidate(); }

        public void set(float v) { value = v; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            p.setColor(skin.meterGround);
            cv.drawRect(0, 0, w, h, p);

            int segs = Math.max(3, skin.segments);
            int cols = Math.max(1, skin.split);
            float frac = Float.isNaN(value) ? 0
                    : Math.max(0f, Math.min(1f, (value - lo) / (hi - lo)));
            int litCount = Math.round(frac * segs);

            float il = th.dpf(skin.insetLead), ir = th.dpf(skin.insetTrail);
            float it = th.dpf(skin.insetTop), ib = th.dpf(skin.insetBottom);
            float aw = Math.max(1, w - il - ir), ah = Math.max(1, h - it - ib);

            int lit = skin.lit(channel), unlit = skin.unlit(channel);

            if (vertical) {
                float colW = aw / cols;
                float barW = colW * 0.72f;
                float segH = ah / segs;
                for (int c = 0; c < cols; c++) {
                    float x = il + c * colW + (colW - barW) / 2f;
                    for (int s = 0; s < segs; s++) {
                        float y = it + ah - (s + 1) * segH;
                        p.setColor(s < litCount ? lit : unlit);
                        cv.drawRect(x, y + segH * 0.14f, x + barW, y + segH * 0.86f, p);
                    }
                }
            } else {
                float segW = aw / segs;
                for (int s = 0; s < segs; s++) {
                    float x = il + s * segW;
                    p.setColor(s < litCount ? lit : unlit);
                    cv.drawRect(x + segW * 0.14f, it, x + segW * 0.86f, it + ah, p);
                }
            }
        }
    }

    // ==================================================================
    //  Inline sparkline: one cell's recent history, small enough for a list row.
    // ==================================================================

    public static class Spark extends View {
        private final Theme th;
        private float[] data = new float[0];
        private int colour;
        private float lo = -135, hi = -60;
        private float alphaMul = 1f;
        private final Paint pl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pf = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), fillPath = new Path();

        public Spark(Context c, Theme theme) {
            super(c);
            th = theme;
            colour = theme.accent;
            pl.setStyle(Paint.Style.STROKE);
            pl.setStrokeWidth(th.dpf(1.3f));
            pl.setStrokeJoin(Paint.Join.ROUND);
            pl.setStrokeCap(Paint.Cap.ROUND);
            pf.setStyle(Paint.Style.FILL);
        }

        public void set(int[] values, int lineColour, float opacity) {
            data = new float[values == null ? 0 : values.length];
            for (int i = 0; i < data.length; i++) data[i] = values[i];
            colour = lineColour;
            alphaMul = opacity;
            invalidate();
        }

        public void range(float l, float h) { lo = l; hi = h; }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || data.length == 0) return;
            if (data.length == 1) {
                pf.setColor(Skin.alpha(colour, 0.9f * alphaMul));
                float y = h * (1f - clamp(data[0]));
                cv.drawCircle(w - th.dpf(2), y, th.dpf(1.6f), pf);
                return;
            }
            float step = w / (data.length - 1);
            path.reset(); fillPath.reset();
            fillPath.moveTo(0, h);
            for (int i = 0; i < data.length; i++) {
                float x = i * step;
                float y = h * (1f - clamp(data[i]));
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            fillPath.lineTo(w, h);
            fillPath.close();
            pf.setColor(Skin.alpha(colour, 0.16f * alphaMul));
            cv.drawPath(fillPath, pf);
            pl.setColor(Skin.alpha(colour, 0.95f * alphaMul));
            cv.drawPath(path, pl);
        }

        private float clamp(float v) {
            return Math.max(0f, Math.min(1f, (v - lo) / (hi - lo)));
        }
    }
}

package com.boresight;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

/**
 * The recent signal history, shared process-wide, plus the renderer that turns it into
 * a bitmap. The notification and the widget cannot host a custom View, so they get a
 * drawn PNG instead; the live screen draws the same data with the same maths.
 */
public final class Trace {

    public static final int TOP = -60, BOTTOM = -135;

    private static final int CAP = 240;
    private static final Trace SHARED = new Trace();

    public static Trace shared() { return SHARED; }

    private final int[] buf = new int[CAP];
    private final long[] when = new long[CAP];
    private int count, head;
    private int peak = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
    private long sum; private int n;

    public synchronized void push(int dbm) {
        if (!Sampler.has(dbm) || dbm == 0) return;
        buf[head] = dbm;
        when[head] = System.currentTimeMillis();
        head = (head + 1) % CAP;
        if (count < CAP) count++;
        if (dbm > peak) peak = dbm;
        if (dbm < low) low = dbm;
        sum += dbm; n++;
    }

    public synchronized void clear() {
        count = 0; head = 0; sum = 0; n = 0;
        peak = Integer.MIN_VALUE; low = Integer.MAX_VALUE;
    }

    public synchronized int size()    { return count; }
    public synchronized int samples() { return n; }
    public synchronized int peak()    { return peak; }
    public synchronized int low()     { return low; }
    public synchronized int avg()     { return n == 0 ? Integer.MAX_VALUE : Math.round((float) sum / n); }

    public synchronized int last() {
        return count == 0 ? Integer.MAX_VALUE : buf[(head - 1 + CAP) % CAP];
    }

    /** Oldest-first copy of the window. */
    public synchronized int[] values() {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = buf[(head - count + i + CAP * 2) % CAP];
        return out;
    }

    public synchronized long[] times() {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = when[(head - count + i + CAP * 2) % CAP];
        return out;
    }

    /** 0 at BOTTOM, 1 at TOP, clamped. */
    public static float frac(int dbm) {
        float f = (dbm - (float) BOTTOM) / (TOP - (float) BOTTOM);
        return Math.max(0f, Math.min(1f, f));
    }

    // ------------------------------------------------------------------ bitmap

    /**
     * Sparkline for the notification and the widget. Transparent background so it sits
     * on whatever surface the system gives it; the line is coloured by the last reading.
     */
    public Bitmap render(int w, int h, int lineColour, int gridColour, int textColour,
                         boolean labels) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);

        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        grid.setColor(gridColour);
        grid.setPathEffect(new DashPathEffect(new float[]{4, 5}, 0));

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(textColour);
        txt.setTextSize(h * 0.15f);
        txt.setTypeface(Typeface.MONOSPACE);

        float padL = labels ? txt.measureText("-135") + 8 : 2;
        float padR = 4, padT = 4, padB = 4;
        float gw = w - padL - padR, gh = h - padT - padB;

        for (int d = TOP; d >= BOTTOM; d -= 25) {
            float y = padT + gh * (1f - frac(d));
            cv.drawLine(padL, y, padL + gw, y, grid);
            if (labels) cv.drawText(String.valueOf(d), 2, y + txt.getTextSize() * 0.35f, txt);
        }

        int[] v = values();
        if (v.length < 2) {
            txt.setColor(textColour);
            cv.drawText("collecting", padL + 6, padT + gh / 2, txt);
            return bmp;
        }

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Theme.alpha(lineColour, 0.18f));

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, h * 0.035f));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(lineColour);

        Path line = new Path(), area = new Path();
        float step = gw / Math.max(1, CAP - 1);
        float x0 = 0, y0 = 0;
        for (int i = 0; i < v.length; i++) {
            float x = padL + gw - (v.length - 1 - i) * step;
            float y = padT + gh * (1f - frac(v[i]));
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, padT + gh); area.lineTo(x, y); x0 = x; }
            else { line.lineTo(x, y); area.lineTo(x, y); }
            if (i == v.length - 1) { y0 = y; area.lineTo(x, padT + gh); }
        }
        area.close();
        cv.drawPath(area, fill);
        cv.drawPath(line, stroke);

        // Head dot, so a frozen graph is obviously frozen.
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(lineColour);
        cv.drawCircle(padL + gw, y0, Math.max(2.5f, h * 0.045f), dot);
        return bmp;
    }

    /**
     * The status bar icon: the live number itself, drawn as a monochrome glyph so the
     * system can tint it like any other small icon. Same trick a now-playing chip uses.
     */
    public static Bitmap statusGlyph(int dbm, String mode) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);

        if ("bars".equals(mode)) {
            int bars = bars(dbm);
            float bw = size / 6.2f, gap = bw * 0.45f;
            float total = bars(0) >= 0 ? 0 : 0;
            float x = (size - (5 * bw + 4 * gap)) / 2f;
            for (int i = 0; i < 5; i++) {
                float hgt = size * (0.22f + 0.17f * i);
                p.setAlpha(i < bars ? 255 : 60);
                cv.drawRoundRect(x, size - hgt - 8, x + bw, size - 8, bw / 3, bw / 3, p);
                x += bw + gap;
            }
            return bmp;
        }

        String s = Sampler.has(dbm) ? String.valueOf(Math.abs(dbm)) : "--";
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        float ts = size * 0.78f;
        p.setTextSize(ts);
        while (p.measureText(s) > size * 0.98f && ts > 10) {
            ts -= 2; p.setTextSize(ts);
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        cv.drawText(s, size / 2f, y, p);
        return bmp;
    }

    /** The usual five-bar ladder, so the icon agrees with the system indicator. */
    public static int bars(int dbm) {
        if (!Sampler.has(dbm) || dbm == 0) return 0;
        if (dbm >= -85)  return 5;
        if (dbm >= -95)  return 4;
        if (dbm >= -105) return 3;
        if (dbm >= -115) return 2;
        return 1;
    }

    public static String label(int dbm) {
        if (!Sampler.has(dbm)) return "no signal";
        if (dbm >= -85)  return "excellent";
        if (dbm >= -95)  return "good";
        if (dbm >= -105) return "fair";
        if (dbm >= -115) return "poor";
        return "very poor";
    }
}

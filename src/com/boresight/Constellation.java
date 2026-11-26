package com.boresight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Constellation diagram, in the airOS sense: the symbol cloud for the modulation the link
 * is currently running, spread by the measured channel quality.
 *
 * Read the header before trusting it. Android exposes no raw IQ �?" demodulated symbols stay
 * inside the modem DSP and no API, privileged or otherwise, hands them out. So these points
 * are not captured samples. What is real is the modulation order (looked up from the
 * reported CQI in the 3GPP tables) and the SINR. From the SINR comes an error vector
 * magnitude, and from that the scatter radius around each ideal symbol point.
 *
 * It is therefore a faithful picture of the measured signal quality drawn on the correct
 * constellation, and it behaves the way the real thing does while you aim: the clouds
 * tighten as SINR climbs and bleed into each other as it falls. It is not a demodulator.
 */
public class Constellation extends View {

    private final Theme th;
    private Skin skin;

    private int bitsPerSymbol = 2;
    private double sinrDb = Double.NaN;
    private String modulation = "QPSK";
    private boolean live = true;

    /** Symbols drawn per cloud. Enough to look like a cloud, few enough to stay smooth. */
    private int density = 28;

    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pIdeal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Fixed seed, advanced once per update. The cloud should shimmer as new symbols
     * arrive, not reshuffle completely on every invalidate from an unrelated redraw.
     */
    private final java.util.Random rnd = new java.util.Random(20260101L);
    private long epoch;

    public Constellation(Context c, Theme theme, Skin s) {
        super(c);
        th = theme;
        skin = s;
        pDot.setStyle(Paint.Style.FILL);
        pAxis.setStyle(Paint.Style.STROKE);
        pAxis.setStrokeWidth(Math.max(1, th.dpf(0.6f)));
        pIdeal.setStyle(Paint.Style.STROKE);
        pIdeal.setStrokeWidth(th.dpf(1f));
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(th.dpf(0.8f));
        pText.setTypeface(Typeface.MONOSPACE);
    }

    public void reskin(Skin s) { skin = s; invalidate(); }

    public void setDensity(int n) { density = Math.max(4, Math.min(160, n)); invalidate(); }

    /** Update from a live reading. */
    public void set(String mod, double sinr) {
        modulation = mod == null ? "QPSK" : mod;
        bitsPerSymbol = Mcs.bitsFor(modulation);
        sinrDb = sinr;
        epoch++;
        live = true;
        invalidate();
    }

    public void setStale() { live = false; invalidate(); }

    public double evmPercent() {
        return Double.isNaN(sinrDb) ? Double.NaN : Mcs.evmPercent(sinrDb);
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Reserve a strip top and bottom for the readouts, so the labels never cross
        // the plot box.
        float headerH = th.sp(skin.szLabel) * 1.9f;
        float footerH = th.sp(skin.szAux) * 1.8f;
        float availH = h - headerH - footerH;
        float size = Math.min(w, availH);
        float cx = w / 2f, cy = headerH + availH / 2f;
        float half = size / 2f - th.dpf(6);
        if (half <= 0) return;

        int accent = skin.accent(Skin.CH_SINR);

        // Axes and the unit box.
        pAxis.setColor(skin.inkRule);
        cv.drawLine(cx - half, cy, cx + half, cy, pAxis);
        cv.drawLine(cx, cy - half, cx, cy + half, pAxis);
        pRing.setColor(Skin.alpha(skin.inkRule, 0.7f));
        cv.drawRect(cx - half, cy - half, cx + half, cy + half, pRing);

        int side = Mcs.sideFor(bitsPerSymbol);          // 2, 4, 8 or 16 per axis
        // Ideal symbol coordinates are the odd multiples of the spacing, normalised so
        // the outermost symbol sits just inside the box.
        float spacing = (2f * half) / side;
        float outer = (side - 1) * spacing / 2f;
        float scale = outer > 0 ? (half * 0.88f) / outer : 1f;

        boolean haveSinr = !Double.isNaN(sinrDb) && Sampler.has((int) sinrDb);
        // Scatter sigma per axis, as a fraction of the RMS symbol amplitude.
        double evm = haveSinr ? Mcs.evmFromSinr(sinrDb) : 0.5;
        // RMS amplitude of this constellation, in the same units as the ideal points.
        double rms = rmsAmplitude(side, spacing) * scale;
        float sigma = (float) (evm * rms / Math.sqrt(2));

        // Decision boundaries, so you can see when the clouds cross them.
        if (side > 2) {
            pAxis.setColor(Skin.alpha(skin.inkRule, 0.5f));
            for (int i = 1; i < side; i++) {
                float off = (-outer + i * spacing - spacing / 2f) * scale;
                cv.drawLine(cx + off, cy - half, cx + off, cy + half, pAxis);
                cv.drawLine(cx - half, cy + off, cx + half, cy + off, pAxis);
            }
        }

        int perCloud = Math.max(3, density / Math.max(1, side * side / 4));
        rnd.setSeed(20260101L + epoch);

        float dotR = Math.max(th.dpf(0.9f), size / (side * 36f));
        float alpha = live ? 0.85f : 0.3f;

        for (int qi = 0; qi < side; qi++) {
            for (int ii = 0; ii < side; ii++) {
                float ix = (-outer + ii * spacing) * scale;
                float qy = (-outer + qi * spacing) * scale;

                // Ideal point marker.
                pIdeal.setColor(Skin.alpha(skin.inkSecondary, live ? 0.55f : 0.2f));
                float t = th.dpf(1.6f);
                cv.drawLine(cx + ix - t, cy - qy, cx + ix + t, cy - qy, pIdeal);
                cv.drawLine(cx + ix, cy - qy - t, cx + ix, cy - qy + t, pIdeal);

                // Scatter cloud.
                for (int k = 0; k < perCloud; k++) {
                    float nx = (float) rnd.nextGaussian() * sigma;
                    float ny = (float) rnd.nextGaussian() * sigma;
                    float px = cx + ix + nx, py = cy - qy + ny;
                    float d = (float) Math.hypot(nx, ny);
                    // Tint by how far the symbol strayed: inside its box, or over the line.
                    float bad = Math.min(1f, d / Math.max(1e-3f, spacing * scale / 2f));
                    int col = Skin.mix(accent, skin.accent(Skin.CH_ALERT), bad * bad);
                    pDot.setColor(Skin.alpha(col, alpha * (0.5f + 0.5f * (1 - bad))));
                    cv.drawCircle(px, py, dotR, pDot);
                }
            }
        }

        // Caption: what you are looking at, and the figure that drives it.
        pText.setTextSize(th.sp(skin.szLabel * 0.95f));
        pText.setColor(skin.inkPrimary);
        pText.setTypeface(skin.face(true, Typeface.BOLD));
        float capY = headerH * 0.72f;
        cv.drawText(modulation, th.dpf(3), capY, pText);

        pText.setTypeface(Typeface.MONOSPACE);
        pText.setColor(skin.inkSecondary);
        pText.setTextSize(th.sp(skin.szAux * 0.9f));
        String right = haveSinr
                ? String.format(java.util.Locale.US, "SINR %.0f dB   EVM %.1f%%", sinrDb, evm * 100)
                : "no SINR reported";
        float tw = pText.measureText(right);
        cv.drawText(right, w - tw - th.dpf(3), capY, pText);

        String foot = "modelled from SINR - not captured IQ";
        pText.setTextSize(th.sp(skin.szAux * 0.82f));
        pText.setColor(Skin.alpha(skin.inkSecondary, 0.8f));
        float fw = pText.measureText(foot);
        cv.drawText(foot, (w - fw) / 2f, h - th.dpf(3), pText);
    }

    /** RMS amplitude of a square QAM constellation whose points sit on the odd grid. */
    private static double rmsAmplitude(int side, float spacing) {
        double outer = (side - 1) * spacing / 2.0;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < side; i++) {
            for (int q = 0; q < side; q++) {
                double x = -outer + i * spacing, y = -outer + q * spacing;
                sum += x * x + y * y;
                n++;
            }
        }
        return Math.sqrt(sum / Math.max(1, n));
    }
}

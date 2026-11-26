package com.boresight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Polar sky plot of the GNSS constellation, doubling as a compass.
 *
 * Azimuth runs around the rim, elevation from the horizon at the edge to overhead at the
 * centre. Satellites carrying a fix are filled, the rest outlined, and each is sized and
 * tinted by its carrier-to-noise density.
 *
 * Two orientations. North-up is the conventional plot. Heading-up rotates the whole sky
 * so the top of the screen is wherever the phone is pointing, which is what you want when
 * you are physically looking for a satellite — or aiming anything else.
 *
 * One correctness detail that matters: GnssStatus reports azimuth against TRUE north,
 * while the rotation vector sensor is referenced to MAGNETIC north. Feeding a raw
 * magnetic heading in here would skew the whole plot by the local declination, which in
 * Colorado is about 8 degrees east. GnssActivity applies the correction before calling
 * setHeading, and this view assumes it has been done.
 */
public class SkyView extends View {

    public static class Sat {
        public int svid;
        public String constellation = "?";
        public float azimuth, elevation, cn0;
        public boolean usedInFix, hasAlmanac, hasEphemeris;

        /**
         * What to print beside the marker.
         *
         * An SVID is only unique within its own constellation, so plotting the bare
         * number put three satellites labelled "31" on one sky and three bars labelled
         * "31" under it. The RINEX letter in front makes each one readable as what it is:
         * G for GPS, R for GLONASS, E for Galileo, C for BeiDou, J for QZSS, I for IRNSS,
         * S for SBAS.
         */
        public String label() {
            return letter(constellation) + svid;
        }

        private static String letter(String c) {
            if (c == null) return "";
            if (c.startsWith("GPS")) return "G";
            if (c.startsWith("GLO")) return "R";
            if (c.startsWith("Gal")) return "E";
            if (c.startsWith("Bei")) return "C";
            if (c.startsWith("QZS")) return "J";
            if (c.startsWith("IRN")) return "I";
            if (c.startsWith("SBAS")) return "S";
            return "";
        }
    }

    private final Theme th;
    private Skin skin;
    private final List<Sat> sats = new ArrayList<>();

    /** Heading of the top of the screen, degrees clockwise from TRUE north. */
    private float headingDeg = Float.NaN;
    private int compassAccuracy = -1;
    private boolean headingUp;

    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SkyView(Context c, Theme theme, Skin s) {
        super(c);
        th = theme;
        skin = s;
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(Math.max(1, th.dpf(0.8f)));
        pText.setTypeface(Typeface.MONOSPACE);
    }

    public void reskin(Skin s) { skin = s; invalidate(); }

    public void set(List<Sat> list) {
        sats.clear();
        if (list != null) sats.addAll(list);
        invalidate();
    }

    /** @param trueHeading degrees clockwise from true north, already declination-corrected */
    public void setHeading(float trueHeading, int accuracy) {
        headingDeg = trueHeading;
        compassAccuracy = accuracy;
        if (headingUp) invalidate();
    }

    public void setHeadingUp(boolean on) { headingUp = on; invalidate(); }

    public boolean isHeadingUp() { return headingUp; }

    public boolean hasHeading() { return !Float.isNaN(headingDeg); }

    public float heading() { return headingDeg; }

    /** How far the plot is rotated on screen. Zero when north-up or with no heading. */
    private float rotation() {
        return headingUp && !Float.isNaN(headingDeg) ? -headingDeg : 0f;
    }

    /** A stable colour per constellation, so the plot reads at a glance. */
    public int colourFor(String constellation) {
        if (constellation == null) return skin.inkSecondary;
        switch (constellation) {
            case "GPS":     return skin.accent(Skin.CH_RSRP);
            case "GLONASS": return skin.accent(Skin.CH_ALERT);
            case "Galileo": return skin.accent(Skin.CH_SINR);
            case "BeiDou":  return skin.accent(Skin.CH_RSRQ);
            case "QZSS":    return skin.accent(Skin.CH_SAT);
            case "IRNSS":   return skin.accent(Skin.CH_FREQ);
            case "SBAS":    return skin.inkSecondary;
            // Not a GNSS constellation: Starlink direct-to-cell, plotted on the same sky.
            case "DTC":     return skin.accent(Skin.CH_ALERT);
            default:        return skin.inkSecondary;
        }
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - th.dpf(18);
        if (r <= 0) return;

        float rot = rotation();

        // Elevation rings at 0, 30 and 60 degrees. Rotation-invariant, so drawn plainly.
        pRing.setColor(skin.inkRule);
        cv.drawCircle(cx, cy, r, pRing);
        cv.drawCircle(cx, cy, r * 2 / 3f, pRing);
        cv.drawCircle(cx, cy, r / 3f, pRing);

        // Spokes every 30 degrees of azimuth, rotated with the sky.
        for (int a = 0; a < 360; a += 30) {
            double rad = Math.toRadians(a + rot - 90);
            float x1 = (float) (cx + Math.cos(rad) * r * 0.06);
            float y1 = (float) (cy + Math.sin(rad) * r * 0.06);
            float x2 = (float) (cx + Math.cos(rad) * r);
            float y2 = (float) (cy + Math.sin(rad) * r);
            pRing.setColor(Skin.alpha(skin.inkRule, a % 90 == 0 ? 1f : 0.45f));
            cv.drawLine(x1, y1, x2, y2, pRing);
        }

        // Cardinals ride around the rim with the rotation but stay upright to read.
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setTextSize(th.sp(11));
        String[] cardinals = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(i * 90 + rot - 90);
            float lx = (float) (cx + Math.cos(rad) * (r + th.dpf(10)));
            float ly = (float) (cy + Math.sin(rad) * (r + th.dpf(10)))
                    + pText.getTextSize() * 0.35f;
            pText.setColor(i == 0 ? skin.accent(Skin.CH_ALERT) : skin.inkSecondary);
            pText.setTypeface(skin.face(false, i == 0 ? Typeface.BOLD : Typeface.NORMAL));
            cv.drawText(cardinals[i], lx, ly, pText);
        }
        pText.setTypeface(Typeface.MONOSPACE);

        pText.setTextSize(th.sp(8));
        pText.setColor(Skin.alpha(skin.inkSecondary, 0.8f));
        cv.drawText("60", cx + th.dpf(14), cy - r / 3f + th.dpf(3), pText);
        cv.drawText("30", cx + th.dpf(14), cy - r * 2 / 3f + th.dpf(3), pText);

        // In heading-up mode, mark the direction the phone itself is pointing.
        if (headingUp && !Float.isNaN(headingDeg)) {
            pFill.setColor(skin.accent(Skin.CH_RSRP));
            Path p = new Path();
            float tip = cy - r - th.dpf(4), halfW = th.dpf(6);
            p.moveTo(cx, tip);
            p.lineTo(cx - halfW, tip + th.dpf(10));
            p.lineTo(cx + halfW, tip + th.dpf(10));
            p.close();
            cv.drawPath(p, pFill);
        }

        // The needle: always points at true north, wherever that has ended up.
        if (!Float.isNaN(headingDeg)) {
            double rad = Math.toRadians(rot - 90);
            float nx = (float) (cx + Math.cos(rad) * r * 0.9);
            float ny = (float) (cy + Math.sin(rad) * r * 0.9);
            pRing.setColor(Skin.alpha(skin.accent(Skin.CH_ALERT), 0.85f));
            pRing.setStrokeWidth(th.dpf(1.8f));
            cv.drawLine(cx, cy, nx, ny, pRing);
            pFill.setColor(skin.accent(Skin.CH_ALERT));
            cv.drawCircle(nx, ny, th.dpf(3f), pFill);
            pRing.setStrokeWidth(Math.max(1, th.dpf(0.8f)));
        }

        if (sats.isEmpty()) {
            pText.setTextSize(th.sp(11));
            pText.setColor(skin.inkSecondary);
            cv.drawText("waiting for satellites", cx, cy, pText);
            pText.setTextAlign(Paint.Align.LEFT);
            drawHeadingReadout(cv, w, h);
            return;
        }

        for (Sat s : sats) {
            if (s.elevation < 0) continue;
            double rad = Math.toRadians(s.azimuth + rot - 90);
            float rr = r * (1f - Math.max(0f, Math.min(90f, s.elevation)) / 90f);
            float x = (float) (cx + Math.cos(rad) * rr);
            float y = (float) (cy + Math.sin(rad) * rr);

            int base = colourFor(s.constellation);
            float dot = th.dpf(3.2f + Math.max(0, Math.min(45, s.cn0)) / 45f * 3.4f);

            if (s.usedInFix) {
                pFill.setColor(base);
                cv.drawCircle(x, y, dot, pFill);
            } else {
                pRing.setColor(Skin.alpha(base, 0.75f));
                pRing.setStrokeWidth(th.dpf(1.3f));
                cv.drawCircle(x, y, dot, pRing);
                pRing.setStrokeWidth(Math.max(1, th.dpf(0.8f)));
            }

            pText.setTextSize(th.sp(7.5f));
            pText.setColor(Skin.alpha(skin.inkPrimary, s.usedInFix ? 0.95f : 0.6f));
            cv.drawText(s.label(), x, y - dot - th.dpf(2), pText);
        }
        pText.setTextAlign(Paint.Align.LEFT);
        drawHeadingReadout(cv, w, h);
    }

    /** Heading in the corner, plus a warning when the magnetometer cannot be trusted. */
    private void drawHeadingReadout(Canvas cv, int w, int h) {
        pText.setTextAlign(Paint.Align.LEFT);
        pText.setTextSize(th.sp(10));

        if (Float.isNaN(headingDeg)) {
            pText.setColor(Skin.alpha(skin.inkSecondary, 0.85f));
            cv.drawText("no compass", th.dpf(2), pText.getTextSize() + th.dpf(1), pText);
            return;
        }

        String s = Math.round(headingDeg) + "° " + Locate.compass(headingDeg)
                + (headingUp ? "  heading up" : "  north up");
        pText.setColor(skin.inkPrimary);
        pText.setTypeface(skin.face(true, Typeface.BOLD));
        cv.drawText(s, th.dpf(2), pText.getTextSize() + th.dpf(1), pText);
        pText.setTypeface(Typeface.MONOSPACE);

        if (compassAccuracy == 0 || compassAccuracy == 1) {
            String warn = compassAccuracy == 0
                    ? "compass unreliable - rotate the phone"
                    : "compass accuracy low";
            pText.setColor(skin.accent(Skin.CH_ALERT));
            pText.setTextSize(th.sp(9));
            cv.drawText(warn, th.dpf(2), h - th.dpf(3), pText);
        }
    }

    // ==================================================================
    //  Carrier-to-noise bars, the other half of a GNSS readout.
    // ==================================================================

    public static class Bars extends View {
        private final Theme th;
        private Skin skin;
        private final List<Sat> sats = new ArrayList<>();
        private final SkyView palette;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Bars(Context c, Theme theme, Skin s, SkyView paletteSource) {
            super(c);
            th = theme; skin = s; palette = paletteSource;
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextAlign(Paint.Align.CENTER);
        }

        public void set(List<Sat> list) {
            sats.clear();
            if (list != null) sats.addAll(list);
            // Strongest first keeps the useful end of the plot stable while things churn.
            java.util.Collections.sort(sats, new java.util.Comparator<Sat>() {
                @Override public int compare(Sat a, Sat b) {
                    return Float.compare(b.cn0, a.cn0);
                }
            });
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || sats.isEmpty()) {
                if (w > 0) {
                    t.setTextAlign(Paint.Align.LEFT);
                    t.setColor(skin.inkSecondary);
                    t.setTextSize(th.sp(10));
                    cv.drawText("no satellites reported", th.dpf(4), h / 2f, t);
                    t.setTextAlign(Paint.Align.CENTER);
                }
                return;
            }

            float labelH = th.dpf(12);
            float plotH = h - labelH - th.dpf(2);
            int n = Math.min(sats.size(), 34);
            float slot = (float) w / n;
            float bw = Math.max(th.dpf(2), slot * 0.62f);

            // Reference line at 30 dB-Hz, the rough threshold for a usable signal.
            p.setColor(Skin.alpha(skin.inkRule, 0.9f));
            p.setStrokeWidth(Math.max(1, th.dpf(0.7f)));
            float refY = plotH * (1f - 30f / 50f);
            cv.drawLine(0, refY, w, refY, p);

            for (int i = 0; i < n; i++) {
                Sat s = sats.get(i);
                float frac = Math.max(0f, Math.min(1f, s.cn0 / 50f));
                float bh = plotH * frac;
                float x = i * slot + (slot - bw) / 2f;
                int col = palette.colourFor(s.constellation);
                p.setColor(s.usedInFix ? col : Skin.alpha(col, 0.35f));
                cv.drawRect(x, plotH - bh, x + bw, plotH, p);

                t.setColor(skin.inkSecondary);
                t.setTextSize(th.sp(7));
                cv.drawText(s.label(), i * slot + slot / 2f, h - th.dpf(2), t);
            }
        }
    }

    /** GnssStatus constellation constants to names. */
    public static String constellationName(int type) {
        switch (type) {
            case 1:  return "GPS";
            case 2:  return "SBAS";
            case 3:  return "GLONASS";
            case 4:  return "QZSS";
            case 5:  return "BeiDou";
            case 6:  return "Galileo";
            case 7:  return "IRNSS";
            default: return "unknown";
        }
    }
}

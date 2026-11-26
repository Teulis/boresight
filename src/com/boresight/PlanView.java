package com.boresight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * A floor plan with the walk drawn on it.
 *
 * Coordinates: the plan bitmap's own pixels are the coordinate system, and everything
 * stored in {@link Walk} is in those. The view keeps a scale and an offset for display
 * and converts at the edges only — so a survey stays valid when the plan is reopened
 * at a different zoom, on a different phone, or after a rotation.
 */
public class PlanView extends View {

    public interface OnTap { void tapped(float planX, float planY); }

    private final Theme th;
    private Skin skin;

    private Bitmap plan;
    private Walk walk;
    private OnTap onTap;

    private float scale = 1f, minScale = 0.2f;
    private float offX, offY;
    private boolean fitted;

    /** Metric, carrier and band currently shown. */
    private Walk.Filter filter = new Walk.Filter();
    /** Recomputed when the filter or the data changes, not on every frame. */
    private java.util.List<Walk.Sample> shown = new java.util.ArrayList<>();

    /** Two-point scale calibration, in plan pixels. */
    private float[] calA, calB;
    private boolean calibrating;

    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;

    public PlanView(Context c, Theme theme, Skin s) {
        super(c);
        th = theme;
        skin = s;
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(th.dpf(2f));
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pMark.setStyle(Paint.Style.STROKE);
        pMark.setStrokeWidth(th.dpf(2f));
        pText.setTextSize(th.dpf(10));
        pText.setFakeBoldText(true);

        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float before = scale;
                scale = Math.max(minScale, Math.min(12f, scale * f));
                // Keep the point under the fingers fixed, or zooming walks the plan
                // off screen and the surveyor loses their place.
                float fx = d.getFocusX(), fy = d.getFocusY();
                offX = fx - (fx - offX) * (scale / before);
                offY = fy - (fy - offY) * (scale / before);
                invalidate();
                return true;
            }
        });

        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                offX -= dx; offY -= dy;
                invalidate();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (plan == null) return false;
                float px = (e.getX() - offX) / scale;
                float py = (e.getY() - offY) / scale;
                if (px < 0 || py < 0 || px > plan.getWidth() || py > plan.getHeight()) {
                    return false;
                }
                if (calibrating) {
                    if (calA == null) calA = new float[]{ px, py };
                    else { calB = new float[]{ px, py }; calibrating = false; }
                    invalidate();
                    return true;
                }
                if (onTap != null) onTap.tapped(px, py);
                invalidate();
                return true;
            }
        });
        setClickable(true);
    }

    public void reskin(Skin s) { skin = s; invalidate(); }
    public void setOnTap(OnTap t) { onTap = t; }
    public void setWalk(Walk w) { walk = w; rebuild(); }

    public void setFilter(Walk.Filter f) { filter = f == null ? new Walk.Filter() : f; rebuild(); }
    public Walk.Filter filter() { return filter; }
    public int metric() { return filter.metric; }
    public int shownCount() { return shown.size(); }

    /**
     * Re-apply the filter. Kept off the draw path: with the average mode on, building
     * the view groups every sample by timestamp, which is far too much work to repeat
     * sixty times a second while someone is panning the plan.
     */
    public void rebuild() {
        shown = walk == null ? new java.util.ArrayList<Walk.Sample>() : walk.view(filter);
        invalidate();
    }

    public void setPlan(Bitmap b) {
        plan = b;
        fitted = false;
        requestLayout();
        invalidate();
    }

    public Bitmap plan() { return plan; }

    // ---------------------------------------------------------------- calibration

    public void beginCalibration() { calibrating = true; calA = null; calB = null; invalidate(); }
    public void cancelCalibration() { calibrating = false; calA = null; calB = null; invalidate(); }
    public boolean calibrating() { return calibrating; }
    public boolean haveCalibrationPair() { return calA != null && calB != null; }

    /** Distance between the two calibration taps, in plan pixels. */
    public double calibrationPixels() {
        if (!haveCalibrationPair()) return Double.NaN;
        return Math.hypot(calB[0] - calA[0], calB[1] - calA[1]);
    }

    public void clearCalibrationMarks() { calA = null; calB = null; invalidate(); }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        c.drawColor(skin.windowFlat());
        if (plan == null) {
            pText.setColor(skin.inkSecondary);
            pText.setTextSize(th.dpf(12));
            c.drawText("No plan loaded", th.dpf(14), getHeight() / 2f, pText);
            return;
        }
        if (!fitted && getWidth() > 0) fit();

        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(offX, offY);
        c.drawBitmap(plan, matrix, null);

        if (walk != null) {
            drawSamples(c);
            drawPath(c);
        }
        drawCalibration(c);
    }

    private void fit() {
        float sx = (float) getWidth() / plan.getWidth();
        float sy = (float) getHeight() / plan.getHeight();
        scale = Math.min(sx, sy);
        minScale = scale * 0.5f;
        offX = (getWidth() - plan.getWidth() * scale) / 2f;
        offY = (getHeight() - plan.getHeight() * scale) / 2f;
        fitted = true;
    }

    public void fitPlan() { fitted = false; invalidate(); }

    private float sx(float planX) { return planX * scale + offX; }
    private float sy(float planY) { return planY * scale + offY; }

    /**
     * Readings as filled dots.
     *
     * Drawn before the path so the walked line stays visible on top of a dense run of
     * samples; at a slow pace there can be hundreds along one corridor.
     */
    private void drawSamples(Canvas c) {
        float r = Math.max(th.dpf(2.2f), th.dpf(3.4f) * Math.min(1.6f, scale));
        pDot.setStyle(Paint.Style.FILL);
        for (Walk.Sample s : shown) {
            if (!s.placed()) continue;
            int v = Walk.field(s, filter.metric);
            if (v == Integer.MAX_VALUE) continue;
            float x = sx(s.x), y = sy(s.y);
            if (x < -20 || y < -20 || x > getWidth() + 20 || y > getHeight() + 20) continue;
            pDot.setColor(colour(v));
            c.drawCircle(x, y, r, pDot);
        }
    }

    private void drawPath(Canvas c) {
        if (walk.points.size() < 2) { drawMarks(c); return; }
        pLine.setColor(Skin.alpha(skin.inkPrimary, 0.55f));
        for (int i = 1; i < walk.points.size(); i++) {
            Walk.Point a = walk.points.get(i - 1), b = walk.points.get(i);
            c.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), pLine);
        }
        drawMarks(c);
    }

    private void drawMarks(Canvas c) {
        float r = th.dpf(6);
        for (int i = 0; i < walk.points.size(); i++) {
            Walk.Point p = walk.points.get(i);
            float x = sx(p.x), y = sy(p.y);
            boolean last = i == walk.points.size() - 1;
            pMark.setStyle(Paint.Style.FILL);
            pMark.setColor(last && walk.recording
                    ? skin.accent(Skin.CH_ALERT) : skin.windowFlat());
            c.drawCircle(x, y, r, pMark);
            pMark.setStyle(Paint.Style.STROKE);
            pMark.setColor(skin.accent(Skin.CH_RSRP));
            c.drawCircle(x, y, r, pMark);

            pText.setColor(skin.inkPrimary);
            pText.setTextSize(th.dpf(9));
            c.drawText(String.valueOf(i + 1), x + r + th.dpf(3), y + th.dpf(3), pText);
        }
    }

    private void drawCalibration(Canvas c) {
        if (calA == null) return;
        pMark.setStyle(Paint.Style.STROKE);
        pMark.setColor(skin.accent(Skin.CH_SAT));
        float r = th.dpf(7);
        c.drawCircle(sx(calA[0]), sy(calA[1]), r, pMark);
        if (calB != null) {
            c.drawCircle(sx(calB[0]), sy(calB[1]), r, pMark);
            pLine.setColor(skin.accent(Skin.CH_SAT));
            c.drawLine(sx(calA[0]), sy(calA[1]), sx(calB[0]), sy(calB[1]), pLine);
        }
    }

    /**
     * Colour for a reading.
     *
     * The RSRP breaks are the ones a DAS acceptance sheet uses rather than a smooth
     * gradient: the question on site is "is this above the criterion", and a
     * continuous ramp makes -94 and -96 look the same when one passes and one does
     * not.
     */
    private int colour(int v) {
        int metric = filter.metric;
        if (metric == Walk.F_SINR) {
            if (v >= 20) return 0xFF2ECC71;
            if (v >= 13) return 0xFF9ACD32;
            if (v >= 5)  return 0xFFF1C40F;
            if (v >= 0)  return 0xFFE67E22;
            return 0xFFE74C3C;
        }
        if (metric == Walk.F_RSRQ) {
            if (v >= -10) return 0xFF2ECC71;
            if (v >= -15) return 0xFF9ACD32;
            if (v >= -20) return 0xFFF1C40F;
            return 0xFFE74C3C;
        }
        if (v >= -80)  return 0xFF2ECC71;   // strong
        if (v >= -90)  return 0xFF9ACD32;
        if (v >= -95)  return 0xFFF1C40F;   // the criterion sits here
        if (v >= -105) return 0xFFE67E22;
        return 0xFFE74C3C;
    }

    /** Legend entries for the current metric: label and colour. */
    public String[][] legend() {
        int metric = filter.metric;
        if (metric == Walk.F_SINR) {
            return new String[][]{{"≥20", "2ECC71"}, {"13–20", "9ACD32"},
                    {"5–13", "F1C40F"}, {"0–5", "E67E22"}, {"<0", "E74C3C"}};
        }
        if (metric == Walk.F_RSRQ) {
            return new String[][]{{"≥-10", "2ECC71"}, {"-15–-10", "9ACD32"},
                    {"-20–-15", "F1C40F"}, {"<-20", "E74C3C"}};
        }
        return new String[][]{{"≥-80", "2ECC71"}, {"-90–-80", "9ACD32"},
                {"-95–-90", "F1C40F"}, {"-105–-95", "E67E22"}, {"<-105", "E74C3C"}};
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}

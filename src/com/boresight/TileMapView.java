package com.boresight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A slippy map with no map library: Web Mercator tile maths, a disk cache, and overlays
 * drawn straight onto the canvas.
 *
 * Tiles come from OpenStreetMap, which is the one network call this app makes. It sends a
 * generic client string and nothing else — no identifiers, no position, no account. Tiles
 * are cached on disk, so a route surveyed once stays readable with no signal, and with
 * tiles switched off the view falls back to a graticule and a scale bar, which is what you
 * want above the treeline anyway.
 */
public class TileMapView extends View {

    public static final int TILE = 256;
    public static final double MIN_ZOOM = 2, MAX_ZOOM = 19;

    /**
     * Below this, marker labels are not drawn at all.
     *
     * Roughly a 500 m screen on this phone, which is about where cells logged from a
     * road stop sitting on top of one another. Tuned by looking at a real survey, not
     * derived: at 15 the labels along a drive were a pile of text, at 16 they read.
     */
    private static final double LABEL_ZOOM = 16;

    private final Theme th;
    private Skin skin;

    private double cLat = 39.7392, cLon = -104.9903;     // Denver, until a fix arrives
    private double zoom = 13;
    private boolean centred;

    private boolean tilesOn = true;
    private boolean dim = true;

    private final LruCache<String, Bitmap> mem;
    private final File diskDir;
    private final ExecutorService net;
    private final java.util.Set<String> inFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private final GestureDetector taps;
    private final ScaleGestureDetector pinch;

    // -------------------------------------------------------------- overlays

    /** One plotted sighting. */
    public static class Mark {
        public double lat, lon;
        public int colour;
        public float radiusDp = 3.5f;
        public String label;
        public Object tag;
        public boolean ring;            // draw as an outline, for lingering or weak hits
        public float alpha = 1f;
        /**
         * Compass degrees this thing faces, or -1 for "not known".
         *
         * A camera's bearing is the difference between "there is a plate reader on that
         * corner" and "it is pointed at the lane you are about to drive down". The
         * OpenStreetMap data carries it and nothing was drawing it.
         */
        public int facingDeg = -1;
    }

    /** An estimated transmitter position with its uncertainty. */
    public static class Fix {
        public double lat, lon;
        public double majorM, minorM, bearingDeg;
        public int colour;
        public String label;
        public Object tag;
    }

    private final List<Mark> marks = new ArrayList<>();
    private final List<Fix> fixes = new ArrayList<>();
    private final List<double[]> track = new ArrayList<>();
    private double meLat = Double.NaN, meLon = Double.NaN, meBearing = Double.NaN;
    private float meAccuracy;

    public interface OnPick { void picked(Object tag, double lat, double lon); }
    private OnPick picker;

    /**
     * Fired after the viewport actually changes, so a readout of it can stay true.
     *
     * Hung off the draw rather than off each gesture: panning, pinching, double-tapping,
     * centre() and fitAll() all end in a redraw, and only one of them was telling anyone.
     * The map's own status line read "z13" while the scale bar said 1 km.
     */
    public interface OnView { void viewChanged(double zoom, double lat, double lon); }
    private OnView viewer;
    private double lastZ = Double.NaN, lastLa, lastLo;

    // -------------------------------------------------------------- paints

    private final Paint pTile = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TileMapView(Context c, Theme theme, Skin s) {
        super(c);
        th = theme;
        skin = s;

        mem = new LruCache<String, Bitmap>(140) {
            @Override protected int sizeOf(String k, Bitmap v) { return 1; }
        };

        // Not getCacheDir(). The whole promise of the tile cache is that a site
        // surveyed once stays readable with no signal, and Android deletes the cache
        // directory whenever it wants storage back — usually at the least convenient
        // moment. Tiles live in app files instead, where only this app removes them.
        File files = c.getExternalFilesDir(null);
        diskDir = new File(files != null ? files : c.getFilesDir(), "tiles");
        if (!diskDir.isDirectory()) diskDir.mkdirs();

        // One-time move of anything the old build left in the cache directory, so an
        // existing survey's tiles are not silently lost on upgrade.
        migrateOldCache(new File(c.getCacheDir(), "tiles"));

        // A small pool with a stack-ordered queue, so the newest visible tiles are
        // fetched first when the user pans quickly.
        net = new ThreadPoolExecutor(3, 3, 20, TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>());

        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setStrokeWidth(Math.max(1, th.dpf(0.6f)));
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(th.dpf(2.2f));
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pText.setTypeface(Typeface.MONOSPACE);

        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                              float dx, float dy) {
                panPixels(dx, dy);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomAround(e.getX(), e.getY(), 1);
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                pick(e.getX(), e.getY());
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });

        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                double before = zoom;
                zoom = clampZoom(zoom + Math.log(d.getScaleFactor()) / Math.log(2));
                if (zoom != before) {
                    // Keep the point under the fingers put.
                    recentreAfterZoom(d.getFocusX(), d.getFocusY(), before);
                }
                invalidate();
                return true;
            }
        });
    }

    // -------------------------------------------------------------- config

    public void reskin(Skin s) { skin = s; invalidate(); }
    public void setTiles(boolean on) { tilesOn = on; invalidate(); }
    public void setDim(boolean on) { dim = on; invalidate(); }
    public void setPicker(OnPick p) { picker = p; }
    public void setOnView(OnView v) { viewer = v; }

    public double zoomLevel() { return zoom; }
    public double centreLat() { return cLat; }
    public double centreLon() { return cLon; }

    public void centre(double lat, double lon) {
        cLat = lat; cLon = lon; centred = true; invalidate();
    }

    public void centre(double lat, double lon, double z) {
        cLat = lat; cLon = lon; zoom = clampZoom(z); centred = true; invalidate();
    }

    public boolean isCentred() { return centred; }

    public void setMe(double lat, double lon, float accuracy, double bearing) {
        meLat = lat; meLon = lon; meAccuracy = accuracy; meBearing = bearing;
        if (!centred && !Double.isNaN(lat)) centre(lat, lon);
        invalidate();
    }

    /** Whether a position for this phone has been supplied at all. */
    public boolean hasMe() { return !Double.isNaN(meLat); }

    public void setMarks(List<Mark> m) {
        marks.clear();
        if (m != null) marks.addAll(m);
        invalidate();
    }

    public void setFixes(List<Fix> f) {
        fixes.clear();
        if (f != null) fixes.addAll(f);
        invalidate();
    }

    public void setTrack(List<double[]> pts) {
        track.clear();
        if (pts != null) track.addAll(pts);
        invalidate();
    }

    /** Frame everything plotted, with a margin. */
    public void fitAll() {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        boolean any = false;
        for (Mark m : marks) {
            minLat = Math.min(minLat, m.lat); maxLat = Math.max(maxLat, m.lat);
            minLon = Math.min(minLon, m.lon); maxLon = Math.max(maxLon, m.lon);
            any = true;
        }
        for (Fix f : fixes) {
            minLat = Math.min(minLat, f.lat); maxLat = Math.max(maxLat, f.lat);
            minLon = Math.min(minLon, f.lon); maxLon = Math.max(maxLon, f.lon);
            any = true;
        }
        if (!Double.isNaN(meLat)) {
            minLat = Math.min(minLat, meLat); maxLat = Math.max(maxLat, meLat);
            minLon = Math.min(minLon, meLon); maxLon = Math.max(maxLon, meLon);
            any = true;
        }
        if (!any || getWidth() == 0) return;

        cLat = (minLat + maxLat) / 2;
        cLon = (minLon + maxLon) / 2;
        centred = true;

        double latSpan = Math.max(1e-5, maxLat - minLat);
        double lonSpan = Math.max(1e-5, maxLon - minLon);
        // Fit the wider of the two spans into the viewport.
        double zLon = Math.log(360.0 * getWidth() / (TILE * lonSpan)) / Math.log(2);
        double zLat = Math.log(170.0 * getHeight() / (TILE * latSpan)) / Math.log(2);
        zoom = clampZoom(Math.min(zLon, zLat) - 0.35);
        invalidate();
    }

    private static double clampZoom(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    // -------------------------------------------------------------- projection

    /** World pixel size at the current fractional zoom. */
    private double worldPx() { return TILE * Math.pow(2, zoom); }

    private static double lonToX(double lon, double world) {
        return (lon + 180.0) / 360.0 * world;
    }

    private static double latToY(double lat, double world) {
        double s = Math.sin(Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat))));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * world;
    }

    private static double xToLon(double x, double world) {
        return x / world * 360.0 - 180.0;
    }

    private static double yToLat(double y, double world) {
        double n = Math.PI - 2 * Math.PI * y / world;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private float screenX(double lon) {
        double world = worldPx();
        return (float) (getWidth() / 2.0 + (lonToX(lon, world) - lonToX(cLon, world)));
    }

    private float screenY(double lat) {
        double world = worldPx();
        return (float) (getHeight() / 2.0 + (latToY(lat, world) - latToY(cLat, world)));
    }

    /** Metres per screen pixel at the view centre. */
    public double metresPerPixel() {
        return 156543.03392 * Math.cos(Math.toRadians(cLat)) / Math.pow(2, zoom);
    }

    // -------------------------------------------------------------- gestures

    private void panPixels(float dx, float dy) {
        double world = worldPx();
        double cx = lonToX(cLon, world) + dx;
        double cy = latToY(cLat, world) + dy;
        cy = Math.max(0, Math.min(world, cy));
        cLon = xToLon(cx, world);
        cLat = yToLat(cy, world);
        centred = true;
        invalidate();
    }

    private void zoomAround(float sx, float sy, double delta) {
        double before = zoom;
        zoom = clampZoom(zoom + delta);
        recentreAfterZoom(sx, sy, before);
        invalidate();
    }

    /** After a zoom change, shift the centre so the focus point stays under the finger. */
    private void recentreAfterZoom(float sx, float sy, double beforeZoom) {
        double worldBefore = TILE * Math.pow(2, beforeZoom);
        double fx = lonToX(cLon, worldBefore) + (sx - getWidth() / 2.0);
        double fy = latToY(cLat, worldBefore) + (sy - getHeight() / 2.0);
        double focusLon = xToLon(fx, worldBefore);
        double focusLat = yToLat(fy, worldBefore);

        double world = worldPx();
        double nx = lonToX(focusLon, world) - (sx - getWidth() / 2.0);
        double ny = latToY(focusLat, world) - (sy - getHeight() / 2.0);
        cLon = xToLon(nx, world);
        cLat = yToLat(Math.max(0, Math.min(world, ny)), world);
        centred = true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        boolean a = pinch.onTouchEvent(e);
        boolean b = taps.onTouchEvent(e);
        return a || b || super.onTouchEvent(e);
    }

    private void pick(float x, float y) {
        if (picker == null) return;
        float best = th.dpf(26);
        Object tag = null;
        double bl = 0, bo = 0;
        for (Fix f : fixes) {
            float d = (float) Math.hypot(screenX(f.lon) - x, screenY(f.lat) - y);
            if (d < best) { best = d; tag = f.tag; bl = f.lat; bo = f.lon; }
        }
        for (Mark m : marks) {
            float d = (float) Math.hypot(screenX(m.lon) - x, screenY(m.lat) - y);
            if (d < best) { best = d; tag = m.tag; bl = m.lat; bo = m.lon; }
        }
        if (tag != null) picker.picked(tag, bl, bo);
    }

    public void zoomBy(double d) { zoomAround(getWidth() / 2f, getHeight() / 2f, d); }

    // -------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        pMark.setColor(skin.darkish() ? 0xFF0A0D11 : 0xFFE8EBF0);
        cv.drawRect(0, 0, w, h, pMark);

        if (tilesOn) drawTiles(cv, w, h);
        else drawGraticule(cv, w, h);

        drawTrack(cv);
        drawMarks(cv);
        drawFixes(cv);
        drawMe(cv);
        drawScale(cv, w, h);
        drawAttribution(cv, w, h);

        if (viewer != null && (zoom != lastZ || cLat != lastLa || cLon != lastLo)) {
            lastZ = zoom; lastLa = cLat; lastLo = cLon;
            // Posted, not called: a listener that touches a view must not run inside a
            // draw pass.
            post(new Runnable() {
                @Override public void run() {
                    if (viewer != null) viewer.viewChanged(zoom, cLat, cLon);
                }
            });
        }
    }

    private void drawTiles(Canvas cv, int w, int h) {
        int z = (int) Math.round(zoom);
        z = (int) Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        int n = 1 << z;

        double zWorld = TILE * (double) n;
        double scale = worldPx() / zWorld;                 // fractional zoom residue
        double drawn = TILE * scale;

        // Centre in this zoom's pixel space.
        double ccx = lonToX(cLon, zWorld), ccy = latToY(cLat, zWorld);
        double originX = w / 2.0 - ccx * scale;
        double originY = h / 2.0 - ccy * scale;

        int x0 = (int) Math.floor(-originX / drawn);
        int y0 = (int) Math.floor(-originY / drawn);
        int x1 = (int) Math.floor((w - originX) / drawn);
        int y1 = (int) Math.floor((h - originY) / drawn);

        boolean missing = false;
        for (int ty = y0; ty <= y1; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = x0; tx <= x1; tx++) {
                int wx = ((tx % n) + n) % n;               // wrap in longitude
                float left = (float) (originX + tx * drawn);
                float top = (float) (originY + ty * drawn);
                RectF dst = new RectF(left, top, (float) (left + drawn), (float) (top + drawn));

                Bitmap bmp = tile(z, wx, ty);
                if (bmp != null) {
                    cv.drawBitmap(bmp, null, dst, pTile);
                } else {
                    missing = true;
                    pMark.setColor(skin.darkish() ? 0xFF0E1218 : 0xFFDFE3EA);
                    cv.drawRect(dst, pMark);
                    pGrid.setColor(Skin.alpha(skin.inkRule, 0.5f));
                    cv.drawRect(dst, pGrid);
                }
            }
        }

        // Night-friendly wash, so the overlays stay the brightest thing on screen.
        if (dim && skin.darkish()) {
            pMark.setColor(0x73000B16);
            cv.drawRect(0, 0, w, h, pMark);
        }

        if (missing) {
            pText.setColor(skin.inkSecondary);
            pText.setTextSize(th.sp(10));
            cv.drawText("tiles loading or offline", th.dpf(8),
                    th.dpf(8) + pText.getTextSize(), pText);
        }
    }

    /** Offline backdrop: a graticule, which is honest about having no basemap. */
    private void drawGraticule(Canvas cv, int w, int h) {
        double mpp = metresPerPixel();
        // Aim for grid lines every ~90 px, snapped to a tidy distance.
        double target = mpp * 90;
        double[] steps = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000,
                          10000, 20000, 50000, 100000};
        double stepM = steps[steps.length - 1];
        for (double s : steps) if (s >= target) { stepM = s; break; }

        double degLat = stepM / Locate.mPerDegLat(cLat);
        double degLon = stepM / Math.max(1, Locate.mPerDegLon(cLat));

        pGrid.setColor(Skin.alpha(skin.inkRule, 0.8f));
        double topLat = yToLat(latToY(cLat, worldPx()) - h / 2.0, worldPx());
        double botLat = yToLat(latToY(cLat, worldPx()) + h / 2.0, worldPx());
        double leftLon = xToLon(lonToX(cLon, worldPx()) - w / 2.0, worldPx());
        double rightLon = xToLon(lonToX(cLon, worldPx()) + w / 2.0, worldPx());

        double startLat = Math.floor(botLat / degLat) * degLat;
        for (double la = startLat; la <= topLat; la += degLat) {
            float y = screenY(la);
            cv.drawLine(0, y, w, y, pGrid);
        }
        double startLon = Math.floor(leftLon / degLon) * degLon;
        for (double lo = startLon; lo <= rightLon; lo += degLon) {
            float x = screenX(lo);
            cv.drawLine(x, 0, x, h, pGrid);
        }

        pText.setColor(skin.inkSecondary);
        pText.setTextSize(th.sp(10));
        cv.drawText("basemap off · grid " + fmtDistance(stepM),
                th.dpf(8), th.dpf(8) + pText.getTextSize(), pText);
    }

    private void drawTrack(Canvas cv) {
        if (track.size() < 2) return;
        Path p = new Path();
        boolean first = true;
        for (double[] pt : track) {
            float x = screenX(pt[1]), y = screenY(pt[0]);
            if (first) { p.moveTo(x, y); first = false; } else { p.lineTo(x, y); }
        }
        pLine.setColor(Skin.alpha(skin.accent(Skin.CH_NEIGH), 0.85f));
        pLine.setStrokeWidth(th.dpf(2.2f));
        cv.drawPath(p, pLine);
    }

    private void drawMarks(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float pad = th.dpf(40);
        for (Mark m : marks) {
            float x = screenX(m.lon), y = screenY(m.lat);
            if (x < -pad || y < -pad || x > w + pad || y > h + pad) continue;
            float r = th.dpf(m.radiusDp);
            if (m.facingDeg >= 0) drawFacing(cv, x, y, m);
            if (m.ring) {
                pGrid.setColor(Skin.alpha(m.colour, 0.9f * m.alpha));
                pGrid.setStrokeWidth(th.dpf(1.4f));
                cv.drawCircle(x, y, r, pGrid);
            } else {
                pMark.setColor(Skin.alpha(m.colour, 0.85f * m.alpha));
                cv.drawCircle(x, y, r, pMark);
            }
        }
        drawLabels(cv, w, h);
    }

    /**
     * The wedge a camera looks down.
     *
     * Drawn in screen space from a fixed pixel length rather than from a distance on
     * the ground, because it is not a claim about range. Nothing in the map data says
     * how far one of these reads a plate, and a wedge scaled in metres would be read as
     * saying so. This is a direction marker that stays legible at every zoom.
     *
     * The arc is deliberately wide and faint. The OSM tag is a single bearing, often
     * recorded by somebody standing in the road with a phone compass, so a crisp narrow
     * beam would be a precision the source does not have.
     */
    private void drawFacing(Canvas cv, float x, float y, Mark m) {
        float len = th.dpf(26);
        float half = 26f;                       // degrees either side of the bearing
        // Compass bearings run clockwise from north; Canvas angles run clockwise from
        // east. Hence the -90.
        float start = m.facingDeg - 90f - half;

        android.graphics.RectF box = new android.graphics.RectF(
                x - len, y - len, x + len, y + len);
        pMark.setColor(Skin.alpha(m.colour, 0.16f * m.alpha));
        cv.drawArc(box, start, half * 2f, true, pMark);

        pGrid.setColor(Skin.alpha(m.colour, 0.55f * m.alpha));
        pGrid.setStrokeWidth(th.dpf(1f));
        cv.drawArc(box, start, half * 2f, true, pGrid);
    }

    /**
     * Labels, once the view is close enough that most of them would not collide.
     *
     * A fixed zoom threshold is the wrong test: whether labels overlap depends on how
     * close together the markers are, not on how far in you have zoomed. Driving one
     * road leaves cells a few hundred metres apart and their labels were a pile of
     * overlapping text at the zoom where a city's cells would have been perfectly
     * legible.
     *
     * Two rules, and both are needed. A label is only drawn if its box clears every box
     * already placed, so labels never overlap each other at any zoom. And none are drawn
     * at all below a zoom where the markers are far enough apart for that to leave more
     * than a token few — a sprinkling of survivors across a field of unlabelled rings
     * tells you less than a clean map does.
     *
     * A "suppress everything while the majority collide" rule was tried first and is
     * wrong: the sectors of one site sit tens of metres apart and never separate at any
     * zoom, so one three-sector site permanently silenced the labels of everything
     * around it. Those sectors simply lose all but one label, which is the honest
     * outcome — there is no zoom at which three labels fit on one point.
     */
    private void drawLabels(Canvas cv, int w, int h) {
        if (zoom < LABEL_ZOOM) return;
        float pad = th.dpf(40);
        pText.setTextSize(th.dpf(9.5f));
        pText.setStyle(Paint.Style.FILL);

        List<android.graphics.RectF> boxes = new ArrayList<>();
        List<Mark> keep = new ArrayList<>();
        List<float[]> at = new ArrayList<>();
        float lead = th.dpf(3), rise = th.dpf(3.4f), high = th.dpf(10);

        for (Mark m : marks) {
            if (m.label == null || m.label.isEmpty()) continue;
            float x = screenX(m.lon), y = screenY(m.lat);
            if (x < -pad || y < -pad || x > w + pad || y > h + pad) continue;

            float lx = x + th.dpf(m.radiusDp) + lead, ly = y + rise;
            // Widened but not heightened. Two labels side by side with no gap read as
            // one number — "1062895:312" against "1062895:2" — so the box claims a
            // margin to each side. Padding it vertically as well made it taller than the
            // spacing between markers on one site, which suppressed the lot.
            float gap = th.dpf(5);
            android.graphics.RectF box = new android.graphics.RectF(
                    lx - gap, ly - high,
                    lx + pText.measureText(m.label) + gap, ly + th.dpf(2));

            boolean clash = false;
            for (android.graphics.RectF b : boxes) {
                if (android.graphics.RectF.intersects(b, box)) { clash = true; break; }
            }
            if (clash) continue;
            boxes.add(box);
            keep.add(m);
            at.add(new float[]{lx, ly});
        }

        for (int i = 0; i < keep.size(); i++) {
            Mark m = keep.get(i);
            float[] p = at.get(i);
            // Haloed, so it stays readable over any basemap.
            pText.setStyle(Paint.Style.STROKE);
            pText.setStrokeWidth(th.dpf(2.4f));
            pText.setColor(Skin.alpha(skin.darkish() ? 0xFF000000 : 0xFFFFFFFF, 0.65f));
            cv.drawText(m.label, p[0], p[1], pText);
            pText.setStyle(Paint.Style.FILL);
            pText.setColor(Skin.alpha(skin.inkPrimary, 0.95f * m.alpha));
            cv.drawText(m.label, p[0], p[1], pText);
        }
    }

    private void drawFixes(Canvas cv) {
        double mpp = metresPerPixel();
        for (Fix f : fixes) {
            float x = screenX(f.lon), y = screenY(f.lat);

            // Uncertainty ellipse, oriented by the major axis bearing.
            float a = (float) (f.majorM / mpp), b = (float) (f.minorM / mpp);
            a = Math.max(a, th.dpf(4));
            b = Math.max(b, th.dpf(3));
            cv.save();
            cv.rotate((float) (90 - f.bearingDeg), x, y);
            pFill.setColor(Skin.alpha(f.colour, 0.16f));
            cv.drawOval(new RectF(x - a, y - b, x + a, y + b), pFill);
            pGrid.setColor(Skin.alpha(f.colour, 0.75f));
            pGrid.setStrokeWidth(th.dpf(1.2f));
            cv.drawOval(new RectF(x - a, y - b, x + a, y + b), pGrid);
            cv.restore();

            // A mast glyph: a pin with radiating arcs, so a fix never reads as a sighting.
            pLine.setColor(f.colour);
            pLine.setStrokeWidth(th.dpf(2f));
            float s = th.dpf(9);
            cv.drawLine(x, y, x, y - s * 1.5f, pLine);
            cv.drawLine(x - s * 0.55f, y, x + s * 0.55f, y, pLine);
            for (int i = 1; i <= 2; i++) {
                float rr = s * (0.5f + 0.42f * i);
                RectF arc = new RectF(x - rr, y - s * 1.5f - rr, x + rr, y - s * 1.5f + rr);
                pGrid.setColor(Skin.alpha(f.colour, 0.9f - 0.25f * i));
                pGrid.setStrokeWidth(th.dpf(1.4f));
                cv.drawArc(arc, 210, 120, false, pGrid);
            }

            if (f.label != null && !f.label.isEmpty()) {
                pText.setTextSize(th.sp(10));
                pText.setTypeface(skin.face(true, Typeface.BOLD));
                float tw = pText.measureText(f.label);
                float bx = x - tw / 2 - th.dpf(3), by = y + th.dpf(6);
                pFill.setColor(Skin.alpha(skin.tileFlat(), 0.88f));
                cv.drawRoundRect(new RectF(bx, by, bx + tw + th.dpf(6),
                        by + pText.getTextSize() + th.dpf(5)), th.dpf(3), th.dpf(3), pFill);
                pText.setColor(f.colour);
                cv.drawText(f.label, bx + th.dpf(3),
                        by + pText.getTextSize() + th.dpf(1), pText);
                pText.setTypeface(Typeface.MONOSPACE);
            }
        }
    }

    private void drawMe(Canvas cv) {
        if (Double.isNaN(meLat)) return;
        float x = screenX(meLon), y = screenY(meLat);
        double mpp = metresPerPixel();

        if (meAccuracy > 0) {
            float r = (float) (meAccuracy / mpp);
            if (r > th.dpf(3)) {
                pFill.setColor(Skin.alpha(skin.accent(Skin.CH_RSRP), 0.14f));
                cv.drawCircle(x, y, r, pFill);
                pGrid.setColor(Skin.alpha(skin.accent(Skin.CH_RSRP), 0.5f));
                pGrid.setStrokeWidth(th.dpf(1));
                cv.drawCircle(x, y, r, pGrid);
            }
        }
        pMark.setColor(0xFFFFFFFF);
        cv.drawCircle(x, y, th.dpf(6), pMark);
        pMark.setColor(skin.accent(Skin.CH_RSRP));
        cv.drawCircle(x, y, th.dpf(4.4f), pMark);

        if (!Double.isNaN(meBearing)) {
            cv.save();
            cv.rotate((float) meBearing, x, y);
            Path p = new Path();
            float s = th.dpf(11);
            p.moveTo(x, y - s);
            p.lineTo(x - s * 0.42f, y - s * 0.45f);
            p.lineTo(x + s * 0.42f, y - s * 0.45f);
            p.close();
            pFill.setColor(skin.accent(Skin.CH_RSRP));
            cv.drawPath(p, pFill);
            cv.restore();
        }
    }

    private void drawScale(Canvas cv, int w, int h) {
        double mpp = metresPerPixel();
        double want = mpp * Math.min(w * 0.33, th.dpf(130));
        double[] steps = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000,
                          10000, 20000, 50000, 100000};
        double pick = steps[0];
        for (double s : steps) if (s <= want) pick = s;
        float px = (float) (pick / mpp);

        float x = th.dpf(10), y = h - th.dpf(26);
        pLine.setColor(skin.inkPrimary);
        pLine.setStrokeWidth(th.dpf(1.6f));
        cv.drawLine(x, y, x + px, y, pLine);
        cv.drawLine(x, y - th.dpf(4), x, y + th.dpf(4), pLine);
        cv.drawLine(x + px, y - th.dpf(4), x + px, y + th.dpf(4), pLine);

        pText.setColor(skin.inkPrimary);
        pText.setTextSize(th.sp(10));
        cv.drawText(fmtDistance(pick), x + th.dpf(3), y - th.dpf(6), pText);
    }

    private void drawAttribution(Canvas cv, int w, int h) {
        if (!tilesOn) return;
        String s = "© OpenStreetMap contributors";
        pText.setTextSize(th.sp(8.5f));
        pText.setColor(Skin.alpha(skin.inkSecondary, 0.9f));
        float tw = pText.measureText(s);
        pFill.setColor(Skin.alpha(skin.darkish() ? 0xFF000000 : 0xFFFFFFFF, 0.5f));
        cv.drawRect(w - tw - th.dpf(8), h - pText.getTextSize() - th.dpf(5), w, h, pFill);
        cv.drawText(s, w - tw - th.dpf(4), h - th.dpf(4), pText);
    }

    public static String fmtDistance(double m) {
        if (m >= 1000) {
            double km = m / 1000;
            return (km == Math.floor(km) ? String.valueOf((long) km)
                    : String.format(java.util.Locale.US, "%.1f", km)) + " km";
        }
        return Math.round(m) + " m";
    }

    // -------------------------------------------------------------- tile supply

    /**
     * Tiles that would not download. Remembered so a missing tile is asked for once
     * rather than on every single redraw — without this, panning over a gap in
     * coverage retries the same 404 dozens of times a second.
     */
    private final java.util.Map<String, Long> failed =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 5 * 60 * 1000;

    private Bitmap tile(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Bitmap b = mem.get(key);
        if (b != null) return b;

        File f = tileFile(z, x, y);
        if (f.isFile()) {
            try {
                Bitmap d = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (d != null) {
                    mem.put(key, d);
                    // Touch it, so trimCache keeps what is actually being looked at.
                    f.setLastModified(System.currentTimeMillis());
                    return d;
                }
            } catch (Throwable ignored) {}
        }
        Long when = failed.get(key);
        if (when != null && System.currentTimeMillis() - when < RETRY_AFTER_MS) return null;
        fetch(z, x, y, key, f);
        return null;
    }

    private File tileFile(int z, int x, int y) {
        return new File(diskDir, z + "_" + x + "_" + y + ".png");
    }

    // ------------------------------------------------------------- offline prefetch

    public interface Progress {
        void update(int done, int total, int failed);
        void finished(int done, int total, int failed, boolean cancelled);
    }

    private volatile boolean prefetching, cancelPrefetch;

    public boolean prefetching() { return prefetching; }
    public void cancelPrefetch() { cancelPrefetch = true; }

    /** How many tiles an area would need, so the user can be told before it starts. */
    public int prefetchCount(int zoomOut, int zoomIn) {
        int n = 0;
        for (int z = zoomOut; z <= zoomIn; z++) n += tilesForZoom(z).length;
        return n;
    }

    /** Tile x/y pairs covering the current viewport at a zoom. */
    private int[][] tilesForZoom(int z) {
        // Work in world pixels at the target zoom, the same projection the renderer
        // uses, rather than converting through lat/lon and back.
        double world = TILE * Math.pow(2, z);
        double cx = lonToX(cLon, world), cy = latToY(cLat, world);
        double scale = world / worldPx();
        double halfW = getWidth()  / 2.0 * scale;
        double halfH = getHeight() / 2.0 * scale;

        int x0 = (int) Math.floor((cx - halfW) / TILE);
        int x1 = (int) Math.floor((cx + halfW) / TILE);
        int y0 = (int) Math.floor((cy - halfH) / TILE);
        int y1 = (int) Math.floor((cy + halfH) / TILE);
        int max = 1 << z;
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(max - 1, x1); y1 = Math.min(max - 1, y1);
        if (x1 < x0 || y1 < y0) return new int[0][];
        int[][] out = new int[(x1 - x0 + 1) * (y1 - y0 + 1)][2];
        int i = 0;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) { out[i][0] = x; out[i][1] = y; i++; }
        }
        return out;
    }

    /**
     * Download the visible area at a range of zooms, for working somewhere with no
     * signal — which, for in-building work, is the normal case rather than the
     * exception.
     *
     * Deliberately slow and single-threaded. OpenStreetMap's tile policy forbids bulk
     * downloading, and the spirit of it is that a volunteer-funded server should not
     * be used as a bulk source. One request at a time with a small delay keeps this to
     * roughly the load of a person panning a map, and the caller caps the tile count.
     */
    public void prefetch(final int zoomOut, final int zoomIn, final int cap,
                         final Progress cb) {
        if (prefetching) return;
        prefetching = true;
        cancelPrefetch = false;

        final java.util.List<int[]> work = new java.util.ArrayList<>();
        for (int z = zoomOut; z <= zoomIn && work.size() < cap; z++) {
            for (int[] xy : tilesForZoom(z)) {
                if (work.size() >= cap) break;
                work.add(new int[]{ z, xy[0], xy[1] });
            }
        }
        final int total = work.size();

        new Thread(new Runnable() {
            @Override public void run() {
                int done = 0, bad = 0;
                for (int[] t : work) {
                    if (cancelPrefetch) break;
                    File f = tileFile(t[0], t[1], t[2]);
                    if (f.isFile()) { done++; report(cb, done, total, bad); continue; }
                    if (fetchNow(t[0], t[1], t[2], f)) done++; else bad++;
                    report(cb, done, total, bad);
                    // Politeness delay. Removing this would be the difference between
                    // using the service and abusing it.
                    try { Thread.sleep(120); } catch (InterruptedException e) { break; }
                }
                trimCache(400L * 1024 * 1024);
                final int d = done, b = bad, tt = total;
                final boolean cancelled = cancelPrefetch;
                prefetching = false;
                post(new Runnable() {
                    @Override public void run() {
                        invalidate();
                        if (cb != null) cb.finished(d, tt, b, cancelled);
                    }
                });
            }
        }, "tile-prefetch").start();
    }

    private void report(final Progress cb, final int done, final int total, final int bad) {
        if (cb == null) return;
        post(new Runnable() {
            @Override public void run() { cb.update(done, total, bad); }
        });
    }

    /** Synchronous single-tile fetch, for the prefetch loop. */
    private boolean fetchNow(int z, int x, int y, File dest) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png");
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", "Boresight/2.0 (Android; cell survey tool)");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(9000);
            if (conn.getResponseCode() != 200) return false;
            File tmp = new File(dest.getAbsolutePath() + ".part");
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
            finally {
                try { out.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
            if (!tmp.renameTo(dest)) { tmp.delete(); return false; }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void fetch(final int z, final int x, final int y,
                       final String key, final File dest) {
        if (!inFlight.add(key)) return;
        net.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL("https://tile.openstreetmap.org/"
                            + z + "/" + x + "/" + y + ".png");
                    conn = (HttpURLConnection) u.openConnection();
                    // Generic client string. OSM asks for identification; it gets the app
                    // name and nothing about the person using it.
                    conn.setRequestProperty("User-Agent", "Boresight/2.0 (Android; cell survey tool)");
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(9000);
                    conn.setInstanceFollowRedirects(true);
                    if (conn.getResponseCode() != 200) {
                        failed.put(key, System.currentTimeMillis());
                        return;
                    }

                    File tmp = new File(dest.getAbsolutePath() + ".part");
                    InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[16384];
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        try { out.close(); } catch (Throwable ignored) {}
                        try { in.close(); } catch (Throwable ignored) {}
                    }
                    if (!tmp.renameTo(dest)) tmp.delete();

                    final Bitmap bmp = BitmapFactory.decodeFile(dest.getAbsolutePath());
                    if (bmp != null) {
                        mem.put(key, bmp);
                        post(new Runnable() { @Override public void run() { invalidate(); } });
                    }
                    failed.remove(key);
                } catch (Throwable t) {
                    failed.put(key, System.currentTimeMillis());
                } finally {
                    inFlight.remove(key);
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private void migrateOldCache(File old) {
        if (!old.isDirectory() || old.equals(diskDir)) return;
        File[] fs = old.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            File to = new File(diskDir, f.getName());
            if (!to.exists() && !f.renameTo(to)) f.delete();
        }
        old.delete();
    }

    /** Size of the tile cache on disk, in bytes. */
    public long cacheBytes() {
        long total = 0;
        File[] fs = diskDir.listFiles();
        if (fs != null) for (File f : fs) total += f.length();
        return total;
    }

    public int cacheTiles() {
        File[] fs = diskDir.listFiles();
        return fs == null ? 0 : fs.length;
    }

    public File cacheDir() { return diskDir; }

    /**
     * Keep the cache under a budget, dropping the least recently used tiles.
     *
     * By modification time, which the fetch sets and a cache hit refreshes, so a tile
     * on a route walked regularly survives and one from a job finished months ago does
     * not. Called after a prefetch rather than on every tile, because listing the
     * directory is the expensive part.
     */
    public void trimCache(final long maxBytes) {
        File[] fs = diskDir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= maxBytes) return;

        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : fs) {
            if (total <= maxBytes) break;
            long n = f.length();
            if (f.delete()) total -= n;
        }
    }

    public void clearCache() {
        File[] fs = diskDir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        mem.evictAll();
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        net.shutdownNow();
    }
}

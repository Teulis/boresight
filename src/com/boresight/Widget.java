package com.boresight;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * Home screen and lock screen widget. The whole face is drawn to a bitmap and pushed into
 * a single ImageView, which keeps it on the same skin and the same drawing code as the app
 * instead of reimplementing the look in RemoteViews.
 *
 * It declares the keyguard category, which One UI honours on builds that offer lock screen
 * widgets. Where it does not, the quick settings tile reaches the same numbers from the
 * lock screen, which is why both exist.
 */
public class Widget extends AppWidgetProvider {

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) render(c, m, id);
    }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m,
                                                    int id, Bundle opts) {
        render(c, m, id);
    }

    /** Redraw every placed instance. Called from the service on each sample. */
    public static void pushAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
            if (ids == null) return;
            for (int id : ids) render(c, m, id);
        } catch (Throwable ignored) {}
    }

    private static void render(Context c, AppWidgetManager m, int id) {
        try {
            Bundle o = m.getAppWidgetOptions(id);
            int wDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180) : 180;
            int hDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90) : 90;
            if (wDp <= 0) wDp = 180;
            if (hDp <= 0) hDp = 90;

            Theme th = new Theme(c);
            int w = Math.min(1400, Math.max(240, th.dp(wDp)));
            int h = Math.min(900, Math.max(110, th.dp(hDp)));

            Bitmap face = draw(c, th, w, h);

            RemoteViews rv = new RemoteViews(c.getPackageName(),
                    Notif.layout(c, "widget"));
            rv.setImageViewBitmap(Notif.id(c, "canvas"), face);
            rv.setOnClickPendingIntent(Notif.id(c, "canvas"), PendingIntent.getActivity(
                    c, 7, new Intent(c, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            m.updateAppWidget(id, rv);
        } catch (Throwable ignored) {}
    }

    /**
     * The widget face: the reading large, the trace behind it, and a status strip.
     * Deliberately legible at a glance on a lock screen rather than dense.
     */
    private static Bitmap draw(Context c, Theme th, int w, int h) {
        Skin skin = Skin.current(c);
        Trace trace = Trace.shared();

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = th.dpf(skin.tileCornerRadius + 4);

        // Panel.
        p.setColor(skin.tileFlat());
        cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, th.dpf(skin.tileStrokeWidth)));
        p.setColor(skin.tileStroke);
        cv.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), r, r, p);
        p.setStyle(Paint.Style.FILL);

        int dbm = trace.last();
        boolean have = Sampler.has(dbm);
        int accent = have ? th.grade(dbm) : skin.inkSecondary;

        float pad = th.dpf(10);

        // Trace across the lower two thirds, behind the numbers.
        int[] v = trace.values();
        if (v.length >= 2) {
            float top = h * 0.34f, bot = h - pad * 0.6f;
            Path line = new Path(), area = new Path();
            float gw = w - pad * 2;
            float step = gw / Math.max(1, v.length - 1);
            for (int i = 0; i < v.length; i++) {
                float x = pad + i * step;
                float y = bot - (bot - top) * Trace.frac(v[i]);
                if (i == 0) { line.moveTo(x, y); area.moveTo(x, bot); area.lineTo(x, y); }
                else { line.lineTo(x, y); area.lineTo(x, y); }
                if (i == v.length - 1) area.lineTo(x, bot);
            }
            area.close();
            p.setColor(Skin.alpha(accent, 0.18f));
            cv.drawPath(area, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(th.dpf(1.8f));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(accent);
            cv.drawPath(line, p);
            p.setStyle(Paint.Style.FILL);
        }

        // Big number.
        String big = have ? String.valueOf(dbm) : "--";
        p.setTypeface(skin.face(true, Typeface.BOLD));
        p.setTextSize(h * 0.40f);
        p.setColor(accent);
        cv.drawText(big, pad, pad + h * 0.34f, p);

        float numW = p.measureText(big);
        p.setTextSize(h * 0.16f);
        p.setColor(skin.inkSecondary);
        p.setTypeface(skin.face(false, Typeface.BOLD));
        cv.drawText("dBm", pad + numW + th.dpf(4), pad + h * 0.32f, p);

        // Carrier and tech, right aligned on the top line.
        Sampler.Snap snap = null;
        try {
            Sampler s = new Sampler(c);
            if (s.permitted()) snap = s.sample();
        } catch (Throwable ignored) {}

        String right = snap != null ? snap.title() : "Boresight";
        p.setTextSize(h * 0.155f);
        p.setColor(skin.inkPrimary);
        float rw = p.measureText(right);
        if (rw < w - pad * 2 - numW) {
            cv.drawText(right, w - pad - rw, pad + h * 0.17f, p);
        }

        // Status strip: recording state, neighbour count, trace depth.
        StringBuilder foot = new StringBuilder();
        boolean rec = Prefs.bool(c, Prefs.WARDRIVE_ON, false);
        foot.append(rec ? "REC" : "idle");
        if (snap != null) foot.append("   ").append(snap.neighbours()).append(" nbr");
        if (Sampler.has(trace.peak())) foot.append("   pk ").append(trace.peak());
        p.setTextSize(h * 0.135f);
        p.setTypeface(skin.face(true, Typeface.NORMAL));
        p.setColor(rec ? skin.accent(Skin.CH_ALERT) : skin.inkSecondary);
        cv.drawText(foot.toString(), pad, h - th.dpf(4), p);

        return bmp;
    }
}

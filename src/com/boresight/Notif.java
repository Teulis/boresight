package com.boresight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.widget.RemoteViews;

/**
 * The ongoing notification: a live readout in the shade, a graph when expanded, and the
 * current RSRP drawn straight into the status bar glyph.
 *
 * The number in the status bar is the same trick a now-playing chip uses �?" the small icon
 * is supplied as a bitmap rather than a drawable resource, redrawn on every update, so the
 * system tints and places it like any other notification icon.
 */
public final class Notif {

    public static final String CHANNEL = "live";
    public static final String CHANNEL_ALERT = "alerts";
    public static final int ID = 1001;
    public static final int ID_ALERT = 1002;

    public static final String ACTION_STOP     = "com.boresight.STOP";
    public static final String ACTION_RECORD   = "com.boresight.RECORD_TOGGLE";
    public static final String ACTION_RESET    = "com.boresight.RESET_TRACE";

    public static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel live = new NotificationChannel(CHANNEL, "Live signal",
                NotificationManager.IMPORTANCE_LOW);
        live.setDescription("The ongoing readout while monitoring is running.");
        live.setShowBadge(false);
        live.enableVibration(false);
        live.setSound(null, null);
        nm.createNotificationChannel(live);

        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "Detections",
                NotificationManager.IMPORTANCE_DEFAULT);
        alert.setDescription("Fires when a signature in the detector list matches nearby.");
        alert.setShowBadge(true);
        nm.createNotificationChannel(alert);
    }

    private static PendingIntent action(Context c, String what) {
        Intent i = new Intent(c, MonitorService.class).setAction(what);
        return PendingIntent.getService(c, what.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Build the live notification for a snapshot.
     *
     * @param recording whether survey logging is currently on, shown plainly so there is
     *                  never any doubt about whether the phone is writing a track
     */
    public static Notification build(Context c, Sampler.Snap snap, boolean recording,
                                     int loggedThisSession, String fixNote) {
        Theme th = new Theme(c);
        Skin skin = Skin.current(c);
        Trace trace = Trace.shared();

        int dbm = snap.dbm();
        boolean have = Sampler.has(dbm);
        String iconMode = Prefs.string(c, Prefs.ICON_VALUE, "rsrp");

        String title = snap.title();
        StringBuilder text = new StringBuilder();
        if (have) {
            text.append(dbm).append(" dBm");
            if (snap.serving != null) {
                if (Sampler.has(snap.serving.qual)) text.append("   SINR ").append(snap.serving.qual);
                if (!Double.isNaN(snap.serving.dlMhz)) {
                    text.append("   ").append(Rf.mhz(snap.serving.dlMhz));
                }
                if (Sampler.has(snap.serving.band)) {
                    text.append("   ").append("NR".equals(snap.serving.tech) ? "n" : "B")
                        .append(snap.serving.band);
                }
            }
        } else {
            text.append("no serving signal");
        }
        text.append("   ·   ").append(snap.neighbours()).append(" neighbours");

        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open(c))
                .setColor(th.grade(dbm))
                .setColorized(false);

        if ("none".equals(iconMode)) {
            b.setSmallIcon(resIcon(c));
        } else {
            try {
                Bitmap glyph = Trace.statusGlyph(dbm, iconMode);
                b.setSmallIcon(Icon.createWithBitmap(glyph));
            } catch (Throwable t) {
                b.setSmallIcon(resIcon(c));
            }
        }

        // Expanded: the trace, plus the lines that do not fit the collapsed row.
        if (Prefs.bool(c, Prefs.NOTIF_GRAPH, true)) {
            try {
                int w = 760, h = 230;
                Bitmap graph = trace.render(w, h,
                        th.grade(dbm), skin.inkRule, skin.inkSecondary, true);
                RemoteViews rv = new RemoteViews(c.getPackageName(), layout(c, "notif_graph"));
                rv.setImageViewBitmap(id(c, "graph"), graph);

                StringBuilder line = new StringBuilder();
                line.append(have ? "now " + dbm : "now --");
                if (Sampler.has(trace.peak())) line.append("   peak ").append(trace.peak());
                if (Sampler.has(trace.avg()))  line.append("   avg ").append(trace.avg());
                line.append("   n=").append(trace.samples());
                line.append('\n');
                line.append(recording
                        ? "recording · " + loggedThisSession + " logged"
                        : "not recording");
                if (fixNote != null && !fixNote.isEmpty()) line.append("   ·   ").append(fixNote);

                rv.setTextViewText(id(c, "line"), line.toString());
                rv.setTextColor(id(c, "line"), skin.inkSecondary);

                b.setCustomBigContentView(rv);
                b.setStyle(new Notification.DecoratedCustomViewStyle());
            } catch (Throwable ignored) {
                // A missing resource must never take the service down.
            }
        }

        b.addAction(new Notification.Action.Builder(resIcon(c),
                recording ? "Stop recording" : "Record", action(c, ACTION_RECORD)).build());
        b.addAction(new Notification.Action.Builder(resIcon(c),
                "Reset trace", action(c, ACTION_RESET)).build());
        b.addAction(new Notification.Action.Builder(resIcon(c),
                "Close", action(c, ACTION_STOP)).build());

        return b.build();
    }

    /** Fired when the detector matches something nearby. */
    public static void detection(Context c, Store.Hit h) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String where = Double.isNaN(h.lat) ? "location unknown"
                : String.format(java.util.Locale.US, "%.5f, %.5f", h.lat, h.lon);
        Notification n = new Notification.Builder(c, CHANNEL_ALERT)
                .setContentTitle((h.vendor == null || h.vendor.isEmpty() ? "Signature match" : h.vendor)
                        + "  ·  " + h.confidence + "% rule confidence")
                .setContentText((h.name == null ? "(no name)" : h.name) + "   " + where)
                .setStyle(new Notification.BigTextStyle().bigText(
                        "Rule: " + h.rule + "\nSeen: " + (h.name == null ? "(no name)" : h.name)
                        + "\nMedium: " + h.medium
                        + (Sampler.has(h.rssi) ? "\nSignal: " + h.rssi + " dBm" : "")
                        + "\nAt: " + where
                        + "\n\nThis is a pattern match on a broadcast name or MAC prefix, "
                        + "not a confirmed identification."))
                .setSmallIcon(resIcon(c))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 3,
                        new Intent(c, FlockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
        nm.notify(ID_ALERT + (int) (h.id % 1000), n);
    }

    public static void clear(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }

    // ------------------------------------------------------------------ resources

    /**
     * Resources are looked up by name rather than through R, because this project is
     * built without a generated R class.
     */
    static int id(Context c, String name) {
        return c.getResources().getIdentifier(name, "id", c.getPackageName());
    }

    static int layout(Context c, String name) {
        return c.getResources().getIdentifier(name, "layout", c.getPackageName());
    }

    static int resIcon(Context c) {
        int i = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        return i != 0 ? i : android.R.drawable.ic_dialog_info;
    }

    private Notif() {}
}

package com.boresight;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick settings tile showing the live reading.
 *
 * This is the dependable way to see the numbers from a locked phone: the quick settings
 * panel pulls down over the lock screen on every build, whereas true lock screen widgets
 * come and go between Android and One UI versions. The tile carries the value in its
 * subtitle and the number itself as its icon, and tapping it starts or stops monitoring.
 */
public class StatsTile extends TileService {

    @Override public void onStartListening() {
        super.onStartListening();
        paint();
    }

    @Override public void onClick() {
        super.onClick();
        if (MonitorService.running) {
            MonitorService.stop(this);
        } else {
            MonitorService.start(this);
        }
        paint();
    }

    @Override public void onTileAdded() {
        super.onTileAdded();
        paint();
    }

    private void paint() {
        Tile t = getQsTile();
        if (t == null) return;

        int dbm = Trace.shared().last();
        boolean have = Sampler.has(dbm);
        boolean on = MonitorService.running;

        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Boresight");

        String sub;
        if (!on) {
            sub = "off";
        } else if (have) {
            sub = dbm + " dBm · " + Trace.label(dbm);
        } else {
            sub = "no signal";
        }
        t.setSubtitle(sub);

        try {
            if (have && on) {
                t.setIcon(Icon.createWithBitmap(Trace.statusGlyph(dbm,
                        Prefs.string(this, Prefs.ICON_VALUE, "rsrp"))));
            } else {
                t.setIcon(Icon.createWithResource(this, Notif.resIcon(this)));
            }
        } catch (Throwable ignored) {}

        t.updateTile();
    }

    /** Ask the system to re-read the tile, after a new sample. */
    public static void refresh(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, StatsTile.class));
        } catch (Throwable ignored) {}
    }
}

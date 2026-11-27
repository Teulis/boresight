package com.boresight;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The survey map: every located sighting, the track, and the estimated transmitter
 * positions worked out from them.
 *
 * Layers are all switchable and all persisted, including the basemap itself — with tiles
 * off the view falls back to a graticule, which is the right default once you are out of
 * coverage and nothing new will download anyway.
 */
public class MapActivity extends Activity {

    private Theme th;
    private Skin skin;
    private Drawer drawer;
    private Header header;
    private TileMapView map;
    private FlowLayout chipRow;
    private LinearLayout sheet;
    private TextView sheetTitle, sheetBody, statusLeft, statusRight;

    private Store store;
    private HandlerThread worker;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Fixes keyed by cell identity, recomputed off the main thread. */
    private final Map<String, Locate.Fix> fixes = new HashMap<>();
    private final Map<String, String> fixLabels = new HashMap<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        th = new Theme(this);
        skin = Skin.current(this);
        store = Store.of(this);

        worker = new HandlerThread("map-solve");
        worker.start();
        bg = new Handler(worker.getLooper());

        FrameLayout stack = new FrameLayout(this);
        stack.setBackgroundColor(skin.windowFlat());

        map = new TileMapView(this, th, skin);
        map.setTiles(Prefs.bool(this, Prefs.L_TILES, true));
        map.setPicker(new TileMapView.OnPick() {
            @Override public void picked(Object tag, double lat, double lon) {
                showSheet(tag, lat, lon);
            }
        });
        map.setOnView(new TileMapView.OnView() {
            @Override public void viewChanged(double z, double lat, double lon) {
                paintViewState();
            }
        });
        stack.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stack.addView(buildTopBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        stack.addView(buildControls(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        sheet = buildSheet();
        sheet.setVisibility(View.GONE);
        stack.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        LinearLayout root = th.column(this);
        root.setBackgroundColor(skin.windowFlat());
        header = new Header(this, th, skin, "MAP", new Runnable() {
            @Override public void run() { drawer.toggle(); }
        });
        root.addView(header, Theme.wrap());

        LinearLayout page = th.column(this);
        page.addView(stack, Theme.grow());
        page.addView(statusBar());
        root.addView(page, Theme.grow());

        drawer = Drawer.attach(this, th, skin, root);
        th.useSkin(skin);
        th.systemBars(this);
        Header.inset(this, header, page, th, drawer);

        header.setMenu(new String[]{
                "Fit everything", "Recompute tower fixes",
                "Save this area offline", "Tile cache", "Clear tile cache"
        }, new Header.MenuPick() {
            @Override public void picked(int id) {
                switch (id) {
                    case 0: map.fitAll(); break;
                    case 1: solveFixes(true); break;
                    case 2: offerPrefetch(); break;
                    case 3: cacheInfo(); break;
                    case 4:
                        long before = map.cacheBytes();
                        map.clearCache();
                        toast("Cleared " + (before / 1048576) + " MB of tiles");
                        break;
                }
            }
        });
    }

    // ------------------------------------------------------------ offline tiles

    /**
     * Download the visible area for use with no signal — which indoors is the normal
     * case, not the exception.
     *
     * The count is shown before anything is fetched, and capped, because OpenStreetMap
     * runs on donated hardware and its tile policy forbids bulk downloading. A few
     * hundred tiles for a building you are about to walk is ordinary use; ten thousand
     * for a county is not, and the cap is what keeps that line honest.
     */
    private void offerPrefetch() {
        if (map.prefetching()) {
            map.cancelPrefetch();
            toast("Stopping");
            return;
        }
        final int z = (int) Math.round(map.zoomLevel());
        final int from = Math.max(1, z - 1), to = Math.min(19, z + 2);
        int count = map.prefetchCount(from, to);
        final int cap = 1500;
        final int will = Math.min(count, cap);

        Sheet.of(this, "Save this area offline")
                .text("Downloads what is on screen at zoom " + from + " to " + to
                        + ": " + will + " tiles"
                        + (count > cap ? " (of " + count + ", capped)" : "")
                        + ", roughly " + (will * 15 / 1024 + 1) + " MB.")
                .text("Tiles come from OpenStreetMap, which is volunteer funded and "
                        + "asks not to be used for bulk downloads. This fetches one at "
                        + "a time with a pause between, so it behaves like someone "
                        + "panning a map rather than a scraper. Zoom in to the building "
                        + "first and the count drops sharply.")
                .action("Download", true, new Runnable() {
                    @Override public void run() { startPrefetch(from, to, cap); }
                })
                .close("Cancel")
                .show();
    }

    private void startPrefetch(int from, int to, int cap) {
        toast("Downloading — the menu now says stop");
        map.prefetch(from, to, cap, new TileMapView.Progress() {
            @Override public void update(int done, int total, int failed) {
                statusRight.setText("tiles " + done + "/" + total
                        + (failed > 0 ? "  " + failed + " failed" : ""));
            }
            @Override public void finished(int done, int total, int failed, boolean cancelled) {
                info(cancelled ? "Stopped" : "Area saved",
                        done + " of " + total + " tiles are on the phone"
                        + (failed > 0 ? ", " + failed + " could not be fetched" : "")
                        + ".\n\nCache is now " + (map.cacheBytes() / 1048576) + " MB over "
                        + map.cacheTiles() + " tiles. They live in app storage, not the "
                        + "system cache, so Android will not delete them to free space.");
            }
        });
    }

    private void cacheInfo() {
        info("Tile cache",
                map.cacheTiles() + " tiles  ·  " + (map.cacheBytes() / 1048576) + " MB\n"
                + map.cacheDir().getAbsolutePath()
                + "\n\nStored in app files rather than the system cache directory, so "
                + "Android cannot delete them when storage runs low — a route surveyed "
                + "once stays readable with no signal. Trimmed to 400 MB, oldest first, "
                + "after an offline download.");
    }

    @Override protected void onResume() {
        super.onResume();
        // A map should know where you are without being asked to write it down.
        if (here == null) {
            here = Here.on(this, new Here.OnFix() {
                @Override public void fix(Location l) { showMe(l); }
            });
        }
        here.start(2000);
        reload();
    }

    @Override protected void onPause() {
        super.onPause();
        if (here != null) here.stop();
    }

    /** Draw the position marker, and centre on it the first time one arrives. */
    private void showMe(Location l) {
        if (l == null) return;
        map.setMe(l.getLatitude(), l.getLongitude(), l.getAccuracy(),
                l.hasBearing() ? l.getBearing() : Double.NaN);
        if (!map.isCentred()) {
            map.centre(l.getLatitude(), l.getLongitude(), Math.max(14, map.zoomLevel()));
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (here != null) here.stop();
        worker.quitSafely();
    }

    @Override public void onBackPressed() {
        if (drawer != null && drawer.isOpen()) { drawer.close(); return; }
        if (sheet.getVisibility() == View.VISIBLE) { sheet.setVisibility(View.GONE); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ chrome

    private View buildTopBar() {
        LinearLayout wrap = th.column(this);
        wrap.setBackgroundColor(Skin.alpha(skin.header[0], 0.93f));

        // Wraps rather than scrolls, so every layer toggle is visible at once.
        chipRow = new FlowLayout(this).gap(th.dp(5));
        chipRow.setPadding(th.dp(8), th.dp(7), th.dp(8), th.dp(7));
        wrap.addView(chipRow, Theme.wrap());

        View rule = new View(this);
        rule.setBackgroundColor(skin.headerRule);
        wrap.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair())));

        rebuildChips();
        return wrap;
    }

    private void rebuildChips() {
        chipRow.removeAllViews();
        layerChip("Basemap", Prefs.L_TILES, true, skin.inkSecondary);
        layerChip("Towers", "l_towers", true, skin.accent(Skin.CH_ALERT));
        layerChip("Cells", L_SITES, true, skin.accent(Skin.CH_RSRP));
        layerChip("Serving", Prefs.L_CELLS, true, skin.accent(Skin.CH_RSRP));
        layerChip("Neighbours", Prefs.L_NEIGHBOURS, true, skin.accent(Skin.CH_SINR));
        layerChip("Track", Prefs.L_TRACK, true, skin.accent(Skin.CH_NEIGH));
        layerChip("Wi-Fi", Prefs.L_WIFI, false, skin.accent(Skin.CH_FREQ));
        layerChip("Detections", Prefs.L_FLOCK, true, skin.accent(Skin.CH_ALERT));
        layerChip("Known cameras", Prefs.L_ALPR, true, skin.accent(Skin.CH_RSRQ));
        layerChip("Colour by signal", Prefs.L_HEAT, true, skin.accent(Skin.CH_RSRQ));
    }

    private void layerChip(final String label, final String key, final boolean def,
                           final int colour) {
        final boolean on = Prefs.bool(this, key, def);
        TextView t = th.num(this, label, 11, on ? skin.windowFlat() : skin.inkSecondary);
        t.setTypeface(skin.face(false, Typeface.BOLD));
        t.setPadding(th.dp(10), th.dp(5), th.dp(10), th.dp(5));
        t.setBackground(on ? th.solid(colour, skin.badgeCornerRadius)
                           : skin.badgeBg(th, 0x00000000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, th.dp(5), 0);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.put(MapActivity.this, key, !on);
                if (Prefs.L_TILES.equals(key)) {
                    map.setTiles(!on);
                }
                rebuildChips();
                reload();
            }
        });
        chipRow.addView(t);
    }

    private View buildControls() {
        LinearLayout col = th.column(this);
        col.setPadding(0, 0, th.dp(8), 0);
        col.addView(roundBtn("+", new Runnable() {
            @Override public void run() { map.zoomBy(1); }
        }));
        col.addView(roundBtn("−", new Runnable() {
            @Override public void run() { map.zoomBy(-1); }
        }));
        col.addView(roundBtn("◎", new Runnable() {
            @Override public void run() { centreOnMe(); }
        }));
        col.addView(roundBtn("⤢", new Runnable() {
            @Override public void run() { map.fitAll(); }
        }));
        return col;
    }

    private View roundBtn(String glyph, final Runnable run) {
        TextView t = th.num(this, glyph, 17, skin.inkPrimary);
        t.setGravity(Gravity.CENTER);
        t.setBackground(th.ripple(th.solid(Skin.alpha(skin.tileFlat(), 0.92f), 7)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(42), th.dp(42));
        lp.setMargins(0, th.dp(4), 0, th.dp(4));
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        return t;
    }

    private LinearLayout buildSheet() {
        LinearLayout s = th.column(this);
        s.setBackgroundColor(Skin.alpha(skin.tileFlat(), 0.97f));
        s.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(14));

        View rule = new View(this);
        rule.setBackgroundColor(skin.headerRule);
        s.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, th.hair())));

        sheetTitle = th.num(this, "", 14, skin.inkPrimary);
        sheetTitle.setTypeface(skin.face(true, Typeface.BOLD));
        sheetTitle.setPadding(0, th.dp(8), 0, th.dp(4));
        s.addView(sheetTitle);

        sheetBody = th.num(this, "", 11, skin.inkSecondary);
        sheetBody.setLineSpacing(th.dpf(2f), 1f);
        s.addView(sheetBody);

        LinearLayout row = th.row(this);
        LinearLayout.LayoutParams rlp = Theme.wrap();
        rlp.setMargins(0, th.dp(8), 0, 0);
        row.setLayoutParams(rlp);
        s.addView(row);

        sheetAction(row, "Cell history", new Runnable() {
            @Override public void run() {
                if (sheetIdent == null) return;
                Intent i = new Intent(MapActivity.this, CellActivity.class);
                i.putExtra("ident", sheetIdent);
                startActivity(i);
            }
        });
        // Isolating one cell is the thing a list of overlapping dots cannot do: every
        // other sighting leaves the map and what remains is that cell's own coverage.
        sheetAction(row, "Only this", new Runnable() {
            @Override public void run() {
                if (sheetIdent == null) return;
                only = sheetIdent.equals(only) ? null : sheetIdent;
                reload();
                map.fitAll();
            }
        });
        sheetAction(row, "Close", new Runnable() {
            @Override public void run() {
                sheet.setVisibility(View.GONE);
                if (only != null) { only = null; reload(); }
            }
        });
        return s;
    }

    private void sheetAction(LinearLayout row, String label, final Runnable run) {
        TextView b = th.text(this, label, 12, skin.accent(Skin.CH_RSRP));
        b.setTypeface(skin.face(false, Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(th.dp(8), th.dp(9), th.dp(8), th.dp(9));
        b.setBackground(th.ripple(
                skin.badgeBg(th, Skin.alpha(skin.accent(Skin.CH_RSRP), 0.10f))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(th.dp(2), 0, th.dp(2), 0);
        b.setLayoutParams(lp);
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        row.addView(b);
    }

    private View statusBar() {
        LinearLayout strip = th.row(this);
        strip.setBackgroundColor(skin.header[0]);
        strip.setPadding(th.dp(12), th.dp(6), th.dp(12), th.dp(7));
        statusLeft = th.num(this, "", 10, skin.inkSecondary);
        strip.addView(statusLeft, Theme.weight(1f));
        statusRight = th.num(this, "", 10, skin.inkSecondary);
        statusRight.setGravity(Gravity.END);
        strip.addView(statusRight);
        return strip;
    }

    // ------------------------------------------------------------------ data

    private String sheetIdent;

    /** One marker per cell rather than one per sighting. */
    private static final String L_SITES = "l_sites";

    /** When set, only this identity is drawn — the map reduced to one cell. */
    private String only;

    /**
     * What to call a cell on the map.
     *
     * The same split the driving screen uses: an LTE cell identity is a 20-bit eNB and an
     * 8-bit sector, and NR is a gNB plus a sector whose width is operator-configurable —
     * 12 bits is the common choice and is what this assumes, so the sector can be wrong
     * on a network that chose differently while the site part stays right.
     */
    private String siteLabel(Store.Radio r) {
        String id = r.ident == null ? "" : r.ident;
        try {
            if (id.startsWith("lte:")) {
                long ci = Long.parseLong(id.substring(4));
                return (ci >> 8) + ":" + (ci & 0xFF);
            }
            if (id.startsWith("nr:")) {
                long nci = Long.parseLong(id.substring(3));
                return (nci >> 12) + ":" + (nci & 0xFFF);
            }
        } catch (NumberFormatException ignored) { }
        if (Sampler.has(r.pci)) return "pci " + r.pci;
        return "";
    }

    /**
     * Gather the layers off the main thread, then hand them to the view on it.
     *
     * Every layer here is a database read, and the cell layer is three grouped queries
     * over the whole survey. At 26,000 rows that is quick enough to get away with on the
     * main thread; at the five thousand miles a month this is built for it is an ANR
     * waiting to happen. The view state the queries depend on — where the map is looking
     * and how big it is — is read here, on the thread that owns the view, and passed in.
     */
    private void reload() {
        final double cLat = map.centreLat(), cLon = map.centreLon();
        final double mpp = map.metresPerPixel();
        final int vw = map.getWidth(), vh = map.getHeight();
        // Toggling three chips quickly starts three gathers; only the last one may draw,
        // or an earlier and slower one lands on top of it and the chips lie.
        final int gen = ++loadGen;
        bg.post(new Runnable() {
            @Override public void run() { gather(gen, cLat, cLon, mpp, vw, vh); }
        });
    }

    private int loadGen;
    private Here here;

    private void gather(int gen, double cLat, double cLon, double mpp, int vw, int vh) {
        boolean heat = Prefs.bool(this, Prefs.L_HEAT, true);
        boolean serving = Prefs.bool(this, Prefs.L_CELLS, true);
        boolean neigh = Prefs.bool(this, Prefs.L_NEIGHBOURS, true);
        boolean wifi = Prefs.bool(this, Prefs.L_WIFI, false);
        boolean flock = Prefs.bool(this, Prefs.L_FLOCK, true);
        boolean trackOn = Prefs.bool(this, Prefs.L_TRACK, true);
        boolean sites = Prefs.bool(this, L_SITES, true);

        List<String> kinds = new ArrayList<>();
        if (serving || neigh) kinds.add(Store.KIND_CELL);
        if (wifi) kinds.add(Store.KIND_WIFI);

        List<TileMapView.Mark> marks = new ArrayList<>();
        int cells = 0, aps = 0;

        // Isolating a cell asks the database for that cell, rather than filtering the
        // capped window of recent rows. The window holds the last 6,000 sightings of
        // everything, so a cell heard seven times last week is simply not in it — the
        // first cut of this filtered the window and proudly reported "0 sightings" for a
        // cell whose own panel said seven.
        List<Store.Obs> points = only != null
                ? store.historyOf(only, 4000)
                : (kinds.isEmpty() ? new ArrayList<Store.Obs>()
                                   : store.located(kinds.toArray(new String[0]), 0, 6000));

        if (!points.isEmpty()) {
            for (Store.Obs o : points) {
                if (Double.isNaN(o.lat) || Double.isNaN(o.lon)) continue;
                if (Store.KIND_CELL.equals(o.kind)) {
                    // While one cell is isolated its own sightings are all shown: the
                    // user asked for this cell, not for this cell minus a layer switch.
                    if (only == null && o.serving && !serving) continue;
                    if (only == null && !o.serving && !neigh) continue;
                    cells++;
                } else {
                    aps++;
                }
                TileMapView.Mark m = new TileMapView.Mark();
                m.lat = o.lat; m.lon = o.lon;
                m.tag = o;
                m.radiusDp = o.serving ? 3.6f : 2.8f;
                m.ring = !o.serving;
                if (Store.KIND_WIFI.equals(o.kind)) {
                    m.colour = skin.accent(Skin.CH_FREQ);
                    m.radiusDp = 2.4f;
                } else if (heat) {
                    m.colour = th.grade(o.dbm);
                } else {
                    m.colour = o.carrier != null ? th.hue(o.carrier) : skin.inkSecondary;
                }
                marks.add(m);
            }
        }

        // One marker per cell, rather than one per sighting.
        //
        // A drive down a road leaves hundreds of overlapping dots for the same cell, so
        // tapping picks whichever sample happened to land nearest the finger: you get a
        // moment, not the cell. These are the cells themselves, each drawn where it was
        // heard loudest, which is also the closest the survey ever came to the tower.
        int siteCount = 0;
        if (sites) {
            for (Store.Radio r : store.radios(Store.KIND_CELL, null, 1200)) {
                if (!r.located()) continue;
                if (only != null && !only.equals(r.ident)) continue;
                siteCount++;
                TileMapView.Mark m = new TileMapView.Mark();
                m.lat = r.lat; m.lon = r.lon;
                m.tag = r;
                m.radiusDp = 7.5f;
                m.ring = true;
                m.label = siteLabel(r);
                m.colour = heat ? th.grade(r.best)
                                : (r.carrier != null ? th.hue(r.carrier) : skin.inkPrimary);
                marks.add(m);
            }
        }

        int hits = 0;
        if (flock) {
            for (Store.Hit h : store.hits(800)) {
                if (Double.isNaN(h.lat)) continue;
                hits++;
                TileMapView.Mark m = new TileMapView.Mark();
                m.lat = h.lat; m.lon = h.lon;
                // A radio this phone received is drawn solid in the alert colour; a
                // place where you merely drove past somebody else's pin is drawn as a
                // ring in the known-camera colour, so it reads as belonging to that
                // layer rather than to this one. On a map the two were identical dots,
                // which made a claim look exactly like a measurement.
                boolean mapped = "map".equals(h.medium);
                m.colour = mapped ? skin.accent(Skin.CH_RSRQ) : skin.accent(Skin.CH_ALERT);
                m.ring = mapped;
                m.radiusDp = mapped ? 4f : 5.5f;
                m.tag = h;
                marks.add(m);
            }
        }

        // Known camera positions from OpenStreetMap. Drawn as rings rather than filled
        // dots, and in a different colour from a detection, because these are other
        // people's reports and a measurement this phone made must never look the same
        // as something downloaded.
        int cams = 0;
        if (Prefs.bool(this, Prefs.L_ALPR, true)) {
            // Only what the view can actually show. The OSM table can hold tens of
            // thousands of rows nationwide, and drawing all of them to render the
            // couple of hundred on screen would make panning crawl.
            double halfSpanM = mpp * Math.max(vw, vh) * 0.75;
            double radius = Math.max(500, Math.min(80_000, halfSpanM));
            for (Alpr.Cam cm : Alpr.near(this, cLat, cLon, radius, 4000)) {
                cams++;
                TileMapView.Mark m = new TileMapView.Mark();
                m.lat = cm.lat; m.lon = cm.lon;
                m.colour = skin.accent(Skin.CH_RSRQ);
                m.radiusDp = 4.5f;
                m.ring = true;
                // OSM records which way most of these face. Drawing it is the
                // difference between "there is a reader on that corner" and "it is
                // pointed at the lane you are about to drive down".
                m.facingDeg = cm.dir;
                m.tag = cm;
                marks.add(m);
            }
        }

        List<double[]> track = null;
        if (trackOn) {
            track = new ArrayList<>();
            for (Store.Obs o : store.located(new String[]{Store.KIND_CELL}, 0, 2500)) {
                if (!o.serving) continue;
                track.add(new double[]{o.lat, o.lon});
            }
            // Oldest first, so the line reads as a path.
            java.util.Collections.reverse(track);
        }

        final long allRows = (cells + aps + hits + siteCount == 0)
                ? store.count(null) : 0;
        final List<TileMapView.Mark> readyMarks = marks;
        final List<double[]> readyTrack = track;
        final int fCells = cells, fAps = aps, fHits = hits, fSites = siteCount;
        final int myGen = gen;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (myGen != loadGen) return;
                publish(readyMarks, readyTrack, fCells, fAps, fHits, fSites, allRows);
            }
        });
    }

    /** The zoom and cache readout, refreshed whenever the viewport actually moves. */
    private void paintViewState() {
        if (statusRight == null) return;
        statusRight.setText("z" + Math.round(map.zoomLevel())
                + "   " + (map.cacheBytes() / 1024) + " KB tiles");
    }

    /** Everything that touches a view, back on the thread that owns them. */
    private void publish(List<TileMapView.Mark> marks, List<double[]> track,
                         int cells, int aps, int hits, int siteCount, long allRows) {
        map.setMarks(marks);
        map.setTrack(track);
        // Here owns the marker while this screen is open; a reload must not replace a
        // live fix with the recorder's older one.
        if (!map.hasMe()) showMe(Here.best(this));

        // Say when there is data but nothing is switched on, rather than showing an
        // empty map and letting it look broken.
        if (cells + aps + hits + siteCount == 0 && allRows > 0) {
            statusLeft.setText("every layer is off  ·  " + Theme.count(allRows, "row"));
        } else if (only != null) {
            statusLeft.setText("only " + only + "  ·  "
                    + Theme.count(cells, "sighting"));
        } else {
            statusLeft.setText(Theme.count(siteCount, "cell") + "  " + cells + " pts  "
                    + aps + " ap  " + Theme.count(hits, "hit"));
        }
        paintViewState();

        if (!map.isCentred()) map.fitAll();
        solveFixes(false);
    }

    /**
     * Estimate a position for every identity with enough located sightings. Runs off the
     * main thread because each solve is an iterative least-squares fit.
     */
    private void solveFixes(final boolean announce) {
        if (!Prefs.bool(this, "l_towers", true)) {
            map.setFixes(null);
            return;
        }
        bg.post(new Runnable() {
            @Override public void run() {
                final List<TileMapView.Fix> out = new ArrayList<>();
                fixes.clear();
                fixLabels.clear();
                int good = 0, rejected = 0, weak = 0;
                for (Store.Tally t : store.tallies(Store.KIND_CELL, "COUNT(*) DESC", 120)) {
                    List<Locate.Point> all = Locate.pointsFor(store, t.ident, 600);
                    if (all.size() < 3) continue;

                    // One identity can cover several physically distinct cells when the
                    // network withheld the cell id and we fell back to PCI + EARFCN.
                    // Solve each place it was seen separately, or the fit is a tower
                    // that does not exist, halfway between two cities.
                    List<List<Locate.Point>> groups =
                            Locate.cluster(all, Locate.CLUSTER_GAP_M);

                    int part = 0;
                    for (List<Locate.Point> pts : groups) {
                        part++;
                        if (pts.size() < 3) continue;
                        Locate.Fix f = Locate.solve(pts,
                                Prefs.integer(MapActivity.this, "eirp_dbm", 60),
                                Prefs.real(MapActivity.this, "pl_exponent", 3.0f),
                                true);
                        if (f.used >= 2 && !f.plausible) { rejected++; continue; }
                        if (!f.ok()) continue;
                        if (f.quality < Prefs.integer(MapActivity.this, "fix_min_quality", 25)) {
                            weak++;
                            continue;
                        }
                        good++;

                        // Distinct key per group, so two cells sharing a PCI do not
                        // overwrite each other in the detail sheet.
                        String key = groups.size() > 1 ? t.ident + " #" + part : t.ident;
                        fixes.put(key, f);

                        TileMapView.Fix vf = new TileMapView.Fix();
                        vf.lat = f.lat; vf.lon = f.lon;
                        vf.majorM = f.majorM; vf.minorM = f.minorM;
                        vf.bearingDeg = f.bearingDeg;
                        vf.colour = t.carrier != null ? th.hue(t.carrier)
                                : skin.accent(Skin.CH_ALERT);
                        String label = (t.carrier != null ? t.carrier : t.tech)
                                + "  " + f.quality + "%";
                        vf.label = label;
                        vf.tag = key;
                        fixLabels.put(key, label);
                        out.add(vf);
                    }
                }
                final int n = good, nr = rejected, nw = weak;
                ui.post(new Runnable() {
                    @Override public void run() {
                        map.setFixes(out);
                        if (!announce) return;
                        // Say what was dropped. Silently hiding them looks like the
                        // layer is broken, and the reason is the useful part: sightings
                        // strung along one road cannot fix a position.
                        StringBuilder m = new StringBuilder();
                        m.append(n == 0 ? "No cell has a usable fix yet"
                                        : n + " tower estimate" + (n == 1 ? "" : "s"));
                        if (nr > 0) m.append(" · ").append(nr)
                                     .append(" rejected as impossible");
                        if (nw > 0) m.append(" · ").append(nw).append(" too vague");
                        toast(m.toString());
                    }
                });
            }
        });
    }

    private void centreOnMe() {
        Location fix = Here.best(this);
        if (fix == null) {
            toast(Here.allowed(this)
                    ? "No position yet. Give it a moment, or step outside."
                    : "Location permission is off, so the map cannot place you.");
            return;
        }
        showMe(fix);
        map.centre(fix.getLatitude(), fix.getLongitude(), Math.max(15, map.zoomLevel()));
        // Say so when the only thing available is old. A stale fix draws exactly like a
        // live one, so the map would otherwise state a position it cannot support.
        long age = Here.ageMs(fix);
        if (age > 120_000) {
            toast("Last known position, " + MainActivity.fmtAge(age) + " old");
        }
    }

    // ------------------------------------------------------------------ selection

    private void showSheet(Object tag, double lat, double lon) {
        sheetIdent = null;
        StringBuilder b = new StringBuilder();

        if (tag instanceof String) {                 // a tower estimate
            String ident = (String) tag;
            sheetIdent = ident;
            Locate.Fix f = fixes.get(ident);
            sheetTitle.setText("Estimated tower  ·  " + ident);
            if (f != null) {
                b.append("method    ").append(f.method).append('\n');
                b.append("quality   ").append(f.quality).append(" of 100\n");
                b.append("from      ").append(f.used).append(" sightings");
                if (f.taUsed > 0) b.append(", ").append(f.taUsed).append(" with timing advance");
                b.append('\n');
                b.append("position  ").append(String.format(java.util.Locale.US,
                        "%.6f, %.6f", f.lat, f.lon)).append('\n');
                b.append("ellipse   ").append(Math.round(f.majorM)).append(" x ")
                 .append(Math.round(f.minorM)).append(" m, major ")
                 .append(Math.round(f.bearingDeg)).append("°\n");
                b.append("residual  ").append(Math.round(f.rmsM)).append(" m rms\n");
                b.append("mean range ").append(Math.round(f.meanRangeM)).append(" m\n");
                if (f.fitted) {
                    b.append("model     fitted from TA: ")
                     .append(String.format(java.util.Locale.US, "%.0f dBm EIRP, n=%.2f",
                             f.eirpDbm, f.exponent)).append('\n');
                } else {
                    b.append("model     assumed ")
                     .append(String.format(java.util.Locale.US, "%.0f dBm EIRP, n=%.2f",
                             f.eirpDbm, f.exponent)).append('\n');
                }
                Location me = MonitorService.lastFix();
                if (me != null) {
                    double d = Locate.distanceM(me.getLatitude(), me.getLongitude(),
                            f.lat, f.lon);
                    double br = Locate.bearingTo(me.getLatitude(), me.getLongitude(),
                            f.lat, f.lon);
                    b.append("from you  ").append(TileMapView.fmtDistance(d))
                     .append(" bearing ").append(Math.round(br)).append("° ")
                     .append(Locate.compass(br)).append('\n');
                }
                if (!f.caveat.isEmpty()) b.append('\n').append(f.caveat);
            }
        } else if (tag instanceof Store.Radio) {
            Store.Radio r = (Store.Radio) tag;
            sheetIdent = r.ident;
            String site = siteLabel(r);
            sheetTitle.setText((r.carrier != null ? r.carrier + "  ·  " : "")
                    + (r.tech != null ? r.tech : "cell")
                    + (site.isEmpty() ? "" : "  ·  "
                        + ("NR".equals(r.tech) ? "gNB " : "eNB ") + site));
            b.append("identity  ").append(r.ident).append('\n');
            if (Sampler.has(r.band)) b.append("band      ").append(r.band).append('\n');
            if (Sampler.has(r.arfcn)) b.append("arfcn     ").append(r.arfcn).append('\n');
            if (Sampler.has(r.pci)) b.append("pci       ").append(r.pci).append('\n');
            b.append("sightings ").append(r.count).append('\n');
            if (Sampler.has(r.best)) b.append("best      ").append(r.best).append(" dBm\n");
            if (!Double.isNaN(r.mean)) {
                b.append("mean      ").append(Math.round(r.mean)).append(" dBm\n");
            }
            b.append("first     ").append(Sync.fmt(r.first)).append('\n');
            b.append("last      ").append(Sync.fmt(r.last)).append('\n');
            b.append("loudest   ").append(String.format(java.util.Locale.US,
                    "%.6f, %.6f", r.lat, r.lon)).append('\n');
            Location me = MonitorService.lastFix();
            if (me != null) {
                double d = Locate.distanceM(me.getLatitude(), me.getLongitude(),
                        r.lat, r.lon);
                double br = Locate.bearingTo(me.getLatitude(), me.getLongitude(),
                        r.lat, r.lon);
                b.append("from you  ").append(TileMapView.fmtDistance(d))
                 .append(" bearing ").append(Math.round(br)).append("° ")
                 .append(Locate.compass(br)).append('\n');
            }
            Locate.Fix f = fixes.get(r.ident);
            if (f != null) {
                b.append("\nTower estimate ").append(Math.round(f.majorM)).append(" m, ")
                 .append("quality ").append(f.quality).append(" of 100\n");
            }
            b.append("\nThat position is where this phone heard it loudest, not the "
                   + "tower's own place. Turn on Towers for the estimate.");
        } else if (tag instanceof Store.Obs) {
            Store.Obs o = (Store.Obs) tag;
            sheetIdent = o.ident;
            sheetTitle.setText((o.carrier != null ? o.carrier + "  ·  " : "")
                    + (o.tech != null ? o.tech : o.kind)
                    + (o.serving ? "  ·  serving" : ""));
            if (o.name != null) b.append("name      ").append(o.name).append('\n');
            if (o.ident != null) b.append("identity  ").append(o.ident).append('\n');
            if (Sampler.has(o.dbm)) b.append("signal    ").append(o.dbm).append(" dBm\n");
            if (Sampler.has(o.qual)) b.append("quality   ").append(o.qual).append('\n');
            if (Sampler.has(o.ta)) {
                b.append("timing    ").append(o.ta).append("  (~")
                 .append(Math.round(o.ta * Locate.TA_METRES)).append(" m)\n");
            }
            if (Sampler.has(o.band)) b.append("band      ").append(o.band).append('\n');
            if (Sampler.has(o.arfcn)) b.append("arfcn     ").append(o.arfcn).append('\n');
            if (!Double.isNaN(o.dlMhz)) b.append("downlink  ").append(Rf.mhz(o.dlMhz)).append('\n');
            b.append("at        ").append(String.format(java.util.Locale.US,
                    "%.6f, %.6f  ±%.0f m", o.lat, o.lon, o.acc)).append('\n');
            b.append("seen      ").append(new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(o.ts)));
        } else if (tag instanceof Store.Hit) {
            Store.Hit h = (Store.Hit) tag;
            sheetTitle.setText("Detection  ·  "
                    + (h.vendor == null || h.vendor.isEmpty() ? h.rule : h.vendor));
            b.append("rule      ").append(h.rule).append('\n');
            b.append("pattern   ").append(h.note).append('\n');
            b.append("confidence ").append(h.confidence).append("%\n");
            b.append("seen name ").append(h.name == null ? "(none)" : h.name).append('\n');
            if (h.mac != null) b.append("address   ").append(h.mac).append('\n');
            b.append("medium    ").append(h.medium).append('\n');
            if (Sampler.has(h.rssi)) b.append("signal    ").append(h.rssi).append(" dBm\n");
            b.append("at        ").append(String.format(java.util.Locale.US,
                    "%.6f, %.6f", h.lat, h.lon)).append('\n');
            b.append("when      ").append(new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(h.ts)));
            b.append("\n\nA name or MAC pattern matched. That is a lead, not an "
                   + "identification.");
        }
        sheetBody.setText(b.toString());
        sheet.setVisibility(View.VISIBLE);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** This screen predates Screen, so it carries its own dialog helper. */
    private void info(String title, String message) {
        Sheet.info(this, title, message);
    }
}

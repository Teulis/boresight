package com.boresight;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Indoor walk test against a floor plan, the way it is done with a scanner on site.
 *
 * Load the print, tap where you are standing, walk, tap again. Everything the radio
 * reported in between is laid along the line between the two taps. No GPS is involved
 * and none would help: the whole reason for this screen is that satellites do not
 * reach the middle of a data hall.
 *
 * PDFs are rendered with the platform's own PdfRenderer, so a design package opens
 * directly with no conversion step and no library.
 */
public class WalkActivity extends Screen {

    private static final int REQ_PLAN = 81;

    /**
     * Longest edge of a rendered page, in pixels.
     *
     * A real design sheet is around 2450 by 1580 points. Rendering that at anything
     * like print resolution produces a bitmap of tens of megabytes — PdfRenderer only
     * outputs ARGB_8888, so it is four bytes a pixel with no say in the matter — and
     * the app is killed on a mid-range phone. 2600 keeps a large plan legible when
     * zoomed while staying around 25 MB during the render and much less once stored.
     */
    private static final int MAX_EDGE = 2600;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private Sampler sampler;

    private PlanView view;
    private TextView state, stats, coverageLine, legend;
    private TextView tapButton, metricButton, carrierButton, bandButton;
    private boolean live;

    private Walk walk = new Walk();

    @Override protected String title() { return "WALK TEST"; }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();
        sampler = new Sampler(this);

        LinearLayout head = pane("Plan");
        state = kv(head, "Status", "no plan loaded");
        stats = kv(head, "Walk", "—");
        coverageLine = kv(head, "Coverage", "—");

        LinearLayout r1 = actionRow(head);
        smallAction(r1, "Open plan…", new Runnable() {
            @Override public void run() { pickPlan(); }
        });
        tapButton = smallAction(r1, "Start", new Runnable() {
            @Override public void run() { startOrStop(); }
        });
        smallAction(r1, "Undo tap", new Runnable() {
            @Override public void run() {
                int back = walk.undoLastPoint();
                view.rebuild();
                refresh();
                toast(back == 0 ? "Nothing to undo"
                        : back + " readings returned — tap the right spot");
            }
        });
        smallAction(r1, "Fit", new Runnable() {
            @Override public void run() { view.fitPlan(); }
        });

        LinearLayout r2 = actionRow(head);
        metricButton = smallAction(r2, "RSRP", new Runnable() {
            @Override public void run() { cycleMetric(); }
        });
        carrierButton = smallAction(r2, "All carriers", new Runnable() {
            @Override public void run() { pickCarrier(); }
        });
        bandButton = smallAction(r2, "All bands", new Runnable() {
            @Override public void run() { pickBand(); }
        });
        smallAction(r2, "Scale…", new Runnable() {
            @Override public void run() { calibrate(); }
        });
        smallAction(r2, "Export", new Runnable() {
            @Override public void run() { export(); }
        });
        smallAction(r2, "Clear", new Runnable() {
            @Override public void run() { confirmClear(); }
        });

        legend = th.text(this, "", 10, skin.inkSecondary);
        head.addView(legend);

        hint(head, "Tap where you are standing, walk a straight line to the next "
                 + "identifiable spot, tap again. Readings taken in between are placed "
                 + "along that line by time, so tap at corners and doors rather than "
                 + "once a room — the error is however far your real path strayed from "
                 + "the straight line.");

        LinearLayout box = pane(null);
        view = new PlanView(this, th, skin);
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.height = th.dp(460);
        box.addView(view, lp);
        view.setWalk(walk);
        view.setOnTap(new PlanView.OnTap() {
            @Override public void tapped(float x, float y) { onPlanTap(x, y); }
        });

        reopenLastPlan();
        refresh();
    }

    /**
     * Reopen whatever plan was last loaded.
     *
     * The rendered page is already kept beside the survey, so there is no reason to
     * make someone find the PDF again every time the app restarts — which on a site
     * walk is exactly when they are least able to.
     */
    private void reopenLastPlan() {
        String path = Prefs.string(this, Prefs.LAST_PLAN, null);
        File f = path == null || path.isEmpty() ? null : new File(path);
        // Fall back to the newest rendered plan on disk. The preference can be absent
        // for reasons that have nothing to do with the user — app data cleared, a
        // restore from backup, an upgrade from a build that did not record it — and
        // the plans are sitting right there. Losing the survey over a missing pointer
        // would be a poor trade.
        if (f == null || !f.isFile()) f = newestPlan();
        if (f == null) return;
        final File plan = f;
        pool.execute(new Runnable() {
            @Override public void run() {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap b = BitmapFactory.decodeFile(plan.getAbsolutePath(), o);
                if (b == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        walk.planName = Prefs.string(WalkActivity.this,
                                Prefs.LAST_PLAN_NAME, plan.getName());
                        walk.planFile = plan.getAbsolutePath();
                        float ppm = Prefs.real(WalkActivity.this, Prefs.LAST_PLAN_PPM, 0f);
                        if (ppm > 0) walk.pxPerMetre = ppm;
                        view.setPlan(b);
                        refresh();
                    }
                });
            }
        });
    }

    /** The most recently rendered plan in app storage, or null if there are none. */
    private File newestPlan() {
        File dir = new File(getExternalFilesDir(null), "plans");
        File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) return null;
        File best = null;
        for (File k : kids) {
            if (!k.isFile()) continue;
            if (best == null || k.lastModified() > best.lastModified()) best = k;
        }
        return best;
    }

    @Override protected void onResume() {
        super.onResume();
        live = true;
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        live = false;
    }

    // ------------------------------------------------------------------ capture

    /**
     * Sample twice a second while recording.
     *
     * Faster than the modem's own refresh on purpose: duplicates are harmless because
     * each is timestamped and placed independently, whereas a gap leaves a stretch of
     * corridor with no readings and no way to know it was walked.
     */
    private void tick() {
        if (!live) return;
        if (walk.recording) {
            try {
                Sampler.Snap snap = sampler.sample();
                if (snap != null) {
                    // Every visible cell, sharing one timestamp. Serving-only data
                    // cannot answer "show me AT&T B66", because the phone is served
                    // by exactly one cell at a time.
                    long now = System.currentTimeMillis();
                    for (Sampler.Cell c : snap.cells) {
                        if (c == null) continue;
                        Walk.Sample s = new Walk.Sample();
                        s.ts = now;
                        s.dbm = c.dbm;
                        s.rsrq = c.rsrq;
                        s.sinr = c.qual;
                        s.rssi = c.rssi;
                        s.band = c.band;
                        s.pci = c.pci;
                        s.arfcn = c.arfcn;
                        s.serving = c.serving;
                        s.tech = c.tech == null ? "" : c.tech;
                        s.carrier = c.carrier != null && !c.carrier.isEmpty()
                                ? c.carrier
                                : (snap.operator == null || "-".equals(snap.operator)
                                   ? "" : snap.operator);
                        walk.collect(s);
                    }
                }
            } catch (Throwable ignored) { }
            refresh();
        }
        ui.postDelayed(new Runnable() { @Override public void run() { tick(); } }, 500);
    }

    private void onPlanTap(float x, float y) {
        if (view.plan() == null) { toast("Open a plan first"); return; }
        if (view.calibrating()) { refresh(); return; }
        if (!walk.recording && !walk.points.isEmpty()) {
            toast("Press Start to continue the walk");
            return;
        }
        walk.tap(x, y, System.currentTimeMillis());
        // A tap is the only moment samples become placed, so it is the only moment
        // the drawn set changes. Rebuilding here rather than on every tick keeps the
        // averaging mode — which groups every sample by timestamp — off the 2 Hz
        // path, while still never showing a stale map.
        view.rebuild();
        refresh();
    }

    private void startOrStop() {
        if (view.plan() == null) { toast("Open a plan first"); return; }
        if (walk.recording) {
            walk.stop();
            toast("Stopped");
        } else {
            walk.recording = true;
            toast(walk.points.isEmpty()
                    ? "Tap where you are standing"
                    : "Recording — tap the next position");
        }
        refresh();
    }

    private void refresh() {
        boolean rec = walk.recording;
        tapButton.setText(rec ? "Stop" : "Start");
        state.setText(view.plan() == null ? "no plan loaded"
                : (rec ? "recording — " + walk.pendingCount() + " waiting for the next tap"
                       : "ready"));

        double m = walk.pathMetres();
        stats.setText(walk.points.size() + " points  ·  " + walk.placedCount()
                + " readings"
                + (Double.isNaN(m) ? ""
                   : String.format(Locale.US, "  ·  %.0f m  (%.0f ft)", m, m * 3.28084)));

        Walk.Filter f = view.filter();
        int target = f.metric == Walk.F_DBM ? Das.DEFAULT_RSRP_TARGET
                   : f.metric == Walk.F_SINR ? 5
                   : f.metric == Walk.F_RSSI ? -85 : -15;
        double cov = walk.coverage(f, target);
        coverageLine.setText(Double.isNaN(cov) ? "—"
                : String.format(Locale.US, "%.1f%% at or above %d %s   %s",
                        cov, target, Walk.metricUnit(f.metric),
                        cov >= Das.DEFAULT_COVERAGE_PCT ? "· meets 95%" : "· below 95%"));

        metricButton.setText(Walk.metricName(f.metric));
        carrierButton.setText(f.average ? "Averaged"
                : f.carrier == null ? "All carriers" : f.carrier);
        bandButton.setText(f.band == Integer.MAX_VALUE ? "All bands" : "B" + f.band);

        StringBuilder lg = new StringBuilder();
        for (String[] e : view.legend()) {
            if (lg.length() > 0) lg.append("   ");
            lg.append(e[0]);
        }
        legend.setText("legend  " + lg);
        status(view.shownCount() + " of " + walk.placedCount() + " shown",
                walk.planName.isEmpty() ? "" : walk.planName);
    }

    // ------------------------------------------------------------------ filters

    private void cycleMetric() {
        Walk.Filter f = view.filter();
        f.metric = (f.metric + 1) % 4;
        view.setFilter(f);
        refresh();
    }

    /**
     * Carrier picker, with an averaging entry.
     *
     * "Average of all" is a separate choice rather than the same thing as "no filter":
     * showing every carrier's readings plots several dots per position and whichever
     * carrier is plotted last wins the pixel, which looks like coverage data and is
     * not. Averaging collapses them to one value per position.
     */
    private void pickCarrier() {
        java.util.List<String> cs = walk.carriers();
        final String[] items = new String[cs.size() + 2];
        items[0] = "All carriers (overlaid)";
        items[1] = "Average of all carriers";
        for (int i = 0; i < cs.size(); i++) items[i + 2] = cs.get(i);

        Sheet s = Sheet.of(this, "Carrier");
        for (int i = 0; i < items.length; i++) {
            final int w = i;
            s.choice(items[i], null, false, new Runnable() {
                @Override public void run() {
                    Walk.Filter f = view.filter();
                    f.carrier = w < 2 ? null : items[w];
                    f.average = w == 1;
                    view.setFilter(f);
                    refresh();
                }
            });
        }
        s.close("Cancel").show();
    }

    private void pickBand() {
        java.util.List<int[]> bs = walk.bands();
        final String[] items = new String[bs.size() + 1];
        final int[] vals = new int[bs.size() + 1];
        items[0] = "All bands";
        vals[0] = Integer.MAX_VALUE;
        for (int i = 0; i < bs.size(); i++) {
            int b = bs.get(i)[0];
            vals[i + 1] = b;
            String fam = Das.name(Das.family("LTE", b));
            items[i + 1] = "B" + b + (fam.equals("other") ? "" : "   " + fam);
        }
        Sheet s = Sheet.of(this, "Band");
        for (int i = 0; i < items.length; i++) {
            final int w = i;
            s.choice(items[i], null, false, new Runnable() {
                @Override public void run() {
                    Walk.Filter f = view.filter();
                    f.band = vals[w];
                    view.setFilter(f);
                    refresh();
                }
            });
        }
        s.close("Cancel").show();
    }

    // ------------------------------------------------------------------ the plan

    private void pickPlan() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{ "application/pdf", "image/*" });
        try { startActivityForResult(i, REQ_PLAN); }
        catch (Throwable t) { toast("No picker available"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PLAN || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = Sync.displayName(this, uri);
        if (name.toLowerCase(Locale.US).endsWith(".pdf")) {
            choosePage(uri, name);
        } else {
            loadImage(uri, name);
        }
    }

    private void choosePage(final Uri uri, final String name) {
        int pages = 0;
        try {
            ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
            PdfRenderer r = new PdfRenderer(fd);
            pages = r.getPageCount();
            r.close();
            fd.close();
        } catch (Throwable t) {
            info("Could not open", "That PDF would not open: " + t.getMessage());
            return;
        }
        if (pages <= 0) { toast("Empty PDF"); return; }
        if (pages == 1) { loadPdf(uri, name, 0); return; }

        final String[] labels = new String[pages];
        for (int i = 0; i < pages; i++) labels[i] = "Page " + (i + 1);
        Sheet s = Sheet.of(this, name + " — " + pages + " pages");
        for (int i = 0; i < labels.length; i++) {
            final int w = i;
            s.choice(labels[i], null, false, new Runnable() {
                @Override public void run() { loadPdf(uri, name, w); }
            });
        }
        s.close("Cancel").show();
    }

    private void loadPdf(final Uri uri, final String name, final int page) {
        state.setText("rendering page " + (page + 1) + "…");
        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                String err = null;
                ParcelFileDescriptor fd = null;
                PdfRenderer r = null;
                PdfRenderer.Page p = null;
                try {
                    fd = getContentResolver().openFileDescriptor(uri, "r");
                    r = new PdfRenderer(fd);
                    p = r.openPage(page);
                    int w = p.getWidth(), h = p.getHeight();
                    double k = (double) MAX_EDGE / Math.max(w, h);
                    if (k > 4) k = 4;              // no point upscaling a tiny page
                    int bw = Math.max(1, (int) (w * k)), bh = Math.max(1, (int) (h * k));

                    bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    // PdfRenderer draws only the page content; anything it does not
                    // touch stays transparent and then reads as black on a dark
                    // theme. Paint the sheet white first.
                    new Canvas(bmp).drawColor(Color.WHITE);
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                } finally {
                    if (p != null) try { p.close(); } catch (Throwable ignored) { }
                    if (r != null) try { r.close(); } catch (Throwable ignored) { }
                    if (fd != null) try { fd.close(); } catch (Throwable ignored) { }
                }
                finish(bmp, name + "  p" + (page + 1), err);
            }
        });
    }

    private void loadImage(final Uri uri, final String name) {
        state.setText("loading…");
        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                String err = null;
                try {
                    // Measure first, then decode downsampled. Decoding a 50 megapixel
                    // photograph of a print at full size is an out-of-memory kill.
                    BitmapFactory.Options probe = new BitmapFactory.Options();
                    probe.inJustDecodeBounds = true;
                    InputStream in = getContentResolver().openInputStream(uri);
                    BitmapFactory.decodeStream(in, null, probe);
                    if (in != null) in.close();

                    int sample = 1;
                    while (Math.max(probe.outWidth, probe.outHeight) / sample > MAX_EDGE) {
                        sample *= 2;
                    }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    InputStream in2 = getContentResolver().openInputStream(uri);
                    bmp = BitmapFactory.decodeStream(in2, null, o);
                    if (in2 != null) in2.close();
                    if (bmp == null) err = "that file is not an image this phone can decode";
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                finish(bmp, name, err);
            }
        });
    }

    private void finish(final Bitmap bmp, final String name, final String err) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (bmp == null) {
                    state.setText("no plan loaded");
                    info("Could not load", err == null ? "unknown error" : err);
                    return;
                }
                // A new plan invalidates the old coordinates entirely.
                if (!walk.points.isEmpty() || !walk.samples.isEmpty()) {
                    walk = new Walk();
                    view.setWalk(walk);
                }
                walk.planName = name;
                view.setPlan(bmp);
                savePlan(bmp, name);
                refresh();
                toast(bmp.getWidth() + " × " + bmp.getHeight());
            }
        });
    }

    /** Keep the rendered page, so reopening a survey does not need the original PDF. */
    private void savePlan(Bitmap b, String name) {
        try {
            File dir = new File(getExternalFilesDir(null), "plans");
            dir.mkdirs();
            // Do not append .png to something already called .png; the plan ends
            // up as "Level1.png.png" in every listing.
            String base = name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (base.toLowerCase(Locale.US).endsWith(".png")) {
                base = base.substring(0, base.length() - 4);
            }
            File f = new File(dir, base + ".png");
            FileOutputStream o = new FileOutputStream(f);
            try { b.compress(Bitmap.CompressFormat.PNG, 90, o); } finally { o.close(); }
            walk.planFile = f.getAbsolutePath();
            // Remember it so the next launch reopens this plan rather than an empty
            // screen. A new plan clears the calibration, which belonged to the old one.
            Prefs.put(this, Prefs.LAST_PLAN, f.getAbsolutePath());
            Prefs.put(this, Prefs.LAST_PLAN_NAME, name);
            Prefs.put(this, Prefs.LAST_PLAN_PPM, 0f);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ scale

    /**
     * Two taps and a distance.
     *
     * Every DAS print carries a scale bar — the one in front of me reads 200.00 ft —
     * so the calibration is to tap each end of it and type the number. Feet, because
     * that is what the bar says on a US drawing.
     */
    private void calibrate() {
        if (view.plan() == null) { toast("Open a plan first"); return; }
        if (!view.haveCalibrationPair()) {
            view.beginCalibration();
            info("Set the scale",
                    "Tap the two ends of the scale bar on the plan, then press Scale "
                  + "again and type the distance between them.\n\nThe bar on a CC&N "
                  + "sheet usually reads 200.00 ft.");
            return;
        }
        final double px = view.calibrationPixels();
        final EditText e = new EditText(this);
        e.setHint("distance in feet");
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(skin.inkPrimary);
        Sheet.of(this, "Distance between the marks")
                .text(String.format(Locale.US,
                        "%.0f pixels on the plan. How far is that on the ground?", px))
                .view(e)
                .action("Set", true, new Runnable() {
                    @Override public void run() {
                        try {
                            double feet = Double.parseDouble(e.getText().toString().trim());
                            if (feet <= 0) { toast("Needs a positive distance"); return; }
                            walk.pxPerMetre = px / (feet * 0.3048);
                            Prefs.put(WalkActivity.this, Prefs.LAST_PLAN_PPM,
                                    (float) walk.pxPerMetre);
                            view.clearCalibrationMarks();
                            toast(String.format(Locale.US, "%.1f px per metre",
                                    walk.pxPerMetre));
                            refresh();
                        } catch (Exception ex) { toast("Not a number"); }
                    }
                })
                .close("Cancel")
                .show();
    }

    // ------------------------------------------------------------------ output

    private void export() {
        if (walk.placedCount() == 0) { toast("Nothing recorded yet"); return; }
        // Summary reports under the active carrier/band filter, which is what the
        // person is looking at; the CSV always carries everything.
        final String[] what = { "Copy CSV", "Save survey file", "Summary" };
        Sheet s = Sheet.of(this, "Export");
        for (int i = 0; i < what.length; i++) {
            final int w = i;
            s.choice(what[i], null, false, new Runnable() {
                @Override public void run() {
                    switch (w) {
                        case 0:
                            ClipboardManager cb = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            cb.setPrimaryClip(ClipData.newPlainText("walk", walk.csv()));
                            toast("CSV copied");
                            break;
                        case 1: saveSurvey(); break;
                        case 2: summary(); break;
                    }
                }
            });
        }
        s.close("Cancel").show();
    }

    private void saveSurvey() {
        pool.execute(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    File dir = new File(getExternalFilesDir(null), "walks");
                    dir.mkdirs();
                    String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                            Locale.US).format(new java.util.Date());
                    File f = new File(dir, "walk-" + stamp + ".json");
                    JSONObject o = walk.toJson();
                    FileOutputStream out = new FileOutputStream(f);
                    try { out.write(o.toString(2).getBytes("UTF-8")); } finally { out.close(); }
                    msg = "Saved to\n" + f.getAbsolutePath()
                        + "\n\nThe rendered plan is alongside it in plans/, so the "
                        + "survey reopens without the original PDF.";
                } catch (Throwable t) {
                    msg = "Could not save: " + t.getMessage();
                }
                final String m = msg;
                ui.post(new Runnable() {
                    @Override public void run() { info("Survey", m); }
                });
            }
        });
    }

    private void summary() {
        StringBuilder b = new StringBuilder();
        b.append("plan       ").append(walk.planName).append('\n');
        b.append("points     ").append(walk.points.size()).append('\n');
        b.append("readings   ").append(walk.placedCount()).append('\n');
        double m = walk.pathMetres();
        b.append("path       ").append(Double.isNaN(m) ? "not calibrated"
                : String.format(Locale.US, "%.0f m / %.0f ft", m, m * 3.28084)).append('\n');
        for (int mi : new int[]{ Walk.F_DBM, Walk.F_RSRQ, Walk.F_SINR, Walk.F_RSSI }) {
            Walk.Filter mf = new Walk.Filter();
            mf.metric = mi;
            mf.carrier = view.filter().carrier;
            mf.band = view.filter().band;
            b.append("mean ").append(String.format(Locale.US, "%-5s", Walk.metricName(mi)))
             .append(fmt(walk.mean(mf))).append("  ")
             .append(Walk.metricUnit(mi)).append('\n');
        }
        b.append('\n');
        for (int t : new int[]{ -80, -85, -90, -95, -100, -105 }) {
            Walk.Filter cf = new Walk.Filter();
            cf.metric = Walk.F_DBM;
            cf.carrier = view.filter().carrier;
            cf.band = view.filter().band;
            b.append(String.format(Locale.US, "= %4d dBm   %5s %%%n", t,
                    fmt(walk.coverage(cf, t))));
        }
        b.append("\nThe usual criterion is -95 dBm over 95% of the floor.");
        info("Walk summary", b.toString());
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "—" : String.format(Locale.US, "%.1f", d);
    }

    private void confirmClear() {
        Sheet.of(this, "Clear this walk?")
                .text("Removes every point and reading. The plan stays loaded.")
                .action("Clear", true, new Runnable() {
                    @Override public void run() {
                        walk.clear();
                        view.rebuild();
                        refresh();
                    }
                })
                .close("Cancel")
                .show();
    }
}

package com.boresight;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where the storage went, drawn the way WinDirStat draws it.
 *
 * The scan is the interesting part rather than the picture. Walking shared storage with
 * java.io is fast and works for most of it, but the folders people actually want to
 * understand — other apps' data under Android/data — are closed to an ordinary app, and
 * shelling out per directory across thousands of them would take minutes. So a blocked
 * subtree is handed to the backend as a single `du` call and grafted in whole. One
 * process for the whole subtree, not one per folder.
 *
 * Sizes are apparent file lengths from the direct walk and allocated blocks from du.
 * Those differ for sparse and small files, which is worth knowing but not worth
 * pretending away: the pane says which parts came from where.
 */
public class StorageActivity extends Screen {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private volatile boolean scanning;

    private TreemapView map;
    private TextView crumb, summary, progress;
    private LinearLayout biggest;

    private TreeNode scanRoot;
    private TreeNode showing;
    private final List<TreeNode> stack = new ArrayList<>();
    private File rootDir;

    @Override protected String title() { return "STORAGE"; }

    @Override protected void content() {
        pool = Executors.newSingleThreadExecutor();
        rootDir = Environment.getExternalStorageDirectory();

        LinearLayout head = pane("Volume");
        summary = kv(head, "Space", "—");
        crumb = th.num(this, rootDir.getAbsolutePath(), 11, skin.accent(Skin.CH_RSRP));
        crumb.setTextIsSelectable(true);
        head.addView(crumb);
        progress = th.text(this, "", 10, skin.inkSecondary);
        head.addView(progress);

        LinearLayout row = actionRow(head);
        smallAction(row, "Scan", new Runnable() {
            @Override public void run() { startScan(rootDir); }
        });
        smallAction(row, "Up", new Runnable() {
            @Override public void run() { up(); }
        });
        smallAction(row, "Stop", new Runnable() {
            @Override public void run() { cancel.set(true); }
        });
        smallAction(row, "Scan a path…", new Runnable() {
            @Override public void run() {
                ask("Scan which folder", rootDir.getAbsolutePath(), new Consumer<String>() {
                    @Override public void accept(String p) {
                        if (p == null || p.trim().isEmpty()) return;
                        File f = new File(p.trim());
                        if (!Vfs.isDirectory(StorageActivity.this, f)) {
                            toast("Not a folder"); return;
                        }
                        rootDir = f;
                        startScan(f);
                    }
                });
            }
        });

        LinearLayout box = pane("Map");
        map = new TreemapView(this, th, skin);
        map.setNote("press Scan");
        LinearLayout.LayoutParams lp = Theme.wrap();
        lp.height = th.dp(420);
        box.addView(map, lp);
        map.setOnPick(new TreemapView.OnPick() {
            @Override public void picked(TreeNode n) { pick(n); }
        });
        hint(box, "Area is size. Tap a folder to go into it, Up to come back. Colour is "
                + "by kind — green images, red video, amber audio, violet archives and "
                + "apks — and a folder takes the colour of whatever fills it.");

        LinearLayout big = pane("Largest files");
        biggest = th.column(this);
        big.addView(biggest, Theme.wrap());

        refreshVolume();
    }

    @Override protected void onPause() {
        super.onPause();
        cancel.set(true);
    }

    private void refreshVolume() {
        try {
            StatFs fs = new StatFs(rootDir.getAbsolutePath());
            long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
            summary.setText(TreemapView.human(total - free) + " used of "
                    + TreemapView.human(total) + "   ·   "
                    + TreemapView.human(free) + " free");
        } catch (Throwable t) {
            summary.setText("—");
        }
    }

    // ------------------------------------------------------------------ scanning

    private void startScan(final File dir) {
        if (scanning) { toast("Already scanning"); return; }
        cancel.set(false);
        scanning = true;
        stack.clear();
        map.setNote("scanning…");
        map.setRoot(null);
        biggest.removeAllViews();
        progress.setText("starting");
        refreshVolume();

        pool.execute(new Runnable() {
            @Override public void run() {
                Counter c = new Counter();
                TreeNode n = walk(dir, 0, c);
                scanning = false;
                final TreeNode done = n;
                // Say what the total does and does not cover. Shared storage is usually
                // a fraction of "used", because installed apps and their private data
                // sit under /data, which is closed to shell and Shizuku alike. Without
                // this the two numbers on screen look like a bug.
                long scanned = n == null ? 0 : n.size;
                final String note = cancel.get()
                        ? "stopped after " + c.files + " files"
                        : c.files + " files  ·  " + c.dirs + " folders"
                          + (c.viaBackend > 0 ? "  ·  " + c.viaBackend + " via backend" : "")
                          + "  ·  " + TreemapView.human(scanned) + " here"
                          + (dir.getAbsolutePath().startsWith("/storage")
                             ? ".  Apps and their private data live under /data and are "
                             + "not counted — that needs root."
                             : "");
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (done != null) Kind.assign(done);
                        scanRoot = done;
                        showing = done;
                        progress.setText(note);
                        map.setNote("nothing here");
                        map.setRoot(done);
                        crumb.setText(done == null ? "—" : done.path);
                        paintBiggest(done);
                    }
                });
            }
        });
    }

    private static final class Counter {
        int files, dirs, viaBackend;
        long lastPost;
    }

    /**
     * Walk a directory into a tree.
     *
     * Depth is capped because shared storage contains symlink loops and very deep
     * caches, and an unbounded walk on a phone is how a scanner becomes a hang.
     */
    private TreeNode walk(File dir, int depth, Counter c) {
        if (cancel.get()) return null;
        TreeNode node = new TreeNode();
        node.name = dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName();
        node.path = dir.getAbsolutePath();
        node.dir = true;
        node.children = new ArrayList<>();
        c.dirs++;

        long now = System.currentTimeMillis();
        if (now - c.lastPost > 250) {
            c.lastPost = now;
            final String s = c.files + " files, in " + node.path;
            ui.post(new Runnable() { @Override public void run() { progress.setText(s); } });
        }

        File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) {
            // Either genuinely empty or closed to us. One du call settles both and
            // brings back the whole subtree at once.
            long viaDu = grafted(dir, node, c);
            if (viaDu > 0) { c.viaBackend++; node.size = viaDu; return node; }
            node.size = 0;
            return node;
        }

        long total = 0;
        for (File f : kids) {
            if (cancel.get()) break;
            if (f.isDirectory()) {
                if (depth >= 12) continue;
                TreeNode sub = walk(f, depth + 1, c);
                if (sub != null && sub.size > 0) { node.children.add(sub); total += sub.size; }
            } else {
                long len = f.length();
                if (len <= 0) continue;
                TreeNode leaf = new TreeNode();
                leaf.name = f.getName();
                leaf.path = f.getAbsolutePath();
                leaf.size = len;
                node.children.add(leaf);
                total += len;
                c.files++;
            }
        }
        node.size = total;
        sort(node);
        return node;
    }

    /**
     * Pull a subtree we cannot walk ourselves out of the backend in one call.
     *
     * `du -ak` because it is what toybox actually ships: -a lists files as well as
     * directories, -k fixes the unit at 1 KB so the output parses the same on every
     * build. Parsing lives in DuTree, which is covered by tests — including the
     * depth-first ordering that flattened the tree the first time round.
     *
     * @return total bytes, or 0 if nothing came back
     */
    private long grafted(File dir, TreeNode into, Counter c) {
        if (!Shell.get(this).privileged()) return 0;
        Shell.Result r = Shell.get(this).exec(
                "du -ak " + Vfs.quote(dir.getAbsolutePath()) + " 2>/dev/null", 120);
        if (!r.ran || r.out == null || r.out.trim().isEmpty()) return 0;
        long total = DuTree.parse(r.out, dir.getAbsolutePath(), into);
        c.files += Math.max(0, DuTree.count(into) - 1);
        return total;
    }
    private void sort(TreeNode n) { n.sortBySize(); }

    // ------------------------------------------------------------------ navigation

    private void pick(TreeNode n) {
        if (n.dir && n.children != null && !n.children.isEmpty()) {
            stack.add(showing);
            showing = n;
            map.setRoot(n);
            crumb.setText(n.path);
            paintBiggest(n);
        } else {
            info(n.name, n.path + "\n\n" + TreemapView.human(n.size)
                    + "\n\nTap Up to go back out.");
        }
    }

    private void up() {
        if (stack.isEmpty()) { toast("At the top"); return; }
        showing = stack.remove(stack.size() - 1);
        map.setRoot(showing);
        crumb.setText(showing == null ? "—" : showing.path);
        paintBiggest(showing);
    }

    @Override public void onBackPressed() {
        if (!stack.isEmpty()) { up(); return; }
        super.onBackPressed();
    }

    private void paintBiggest(TreeNode n) {
        biggest.removeAllViews();
        if (n == null) return;
        List<TreeNode> all = new ArrayList<>();
        collectFiles(n, all, 0);
        Collections.sort(all, new Comparator<TreeNode>() {
            @Override public int compare(TreeNode a, TreeNode b) {
                return Long.compare(b.size, a.size);
            }
        });
        int shown = 0;
        for (final TreeNode f : all) {
            if (++shown > 25) break;
            LinearLayout row = th.column(this);
            row.setPadding(0, th.dp(5), 0, th.dp(5));
            row.setBackground(th.ripple(th.solid(0x00000000, 0)));
            row.setClickable(true);
            TextView a = th.num(this, TreemapView.human(f.size) + "   " + f.name, 11,
                    skin.inkPrimary);
            row.addView(a);
            row.addView(th.text(this, f.path, 9, Skin.alpha(skin.inkSecondary, 0.8f)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    info(f.name, f.path + "\n\n" + TreemapView.human(f.size)
                            + "\n\nThe Files screen can open, hex edit or delete it.");
                }
            });
            biggest.addView(row);
        }
        if (shown == 0) {
            biggest.addView(th.text(this, "No files here.", 11, skin.inkSecondary));
        }
    }

    private void collectFiles(TreeNode n, List<TreeNode> out, int depth) {
        if (depth > 14 || out.size() > 20000) return;
        if (n.children == null || n.children.isEmpty()) {
            if (!n.dir) out.add(n);
            return;
        }
        for (TreeNode k : n.children) collectFiles(k, out, depth + 1);
    }

}

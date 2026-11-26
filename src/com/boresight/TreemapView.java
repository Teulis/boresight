package com.boresight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A squarified treemap, in the WinDirStat sense: every file is a rectangle whose area
 * is its size, so what is eating the disk is simply the biggest shape on screen.
 *
 * The layout is the Bruls, Huizing and van Wijk algorithm. The naive alternative —
 * slicing the full width for each item in turn — is a few lines shorter and produces
 * long thin slivers for everything after the first item, which are impossible to
 * compare by eye and impossible to tap. Squarifying keeps each rectangle's aspect
 * ratio as near 1 as it can, which is the whole reason the picture is readable.
 */
public class TreemapView extends View {

    // The node type and the layout maths both live outside this view so they can be
    // tested without a device — see TreeNode, DuTree and Squarify.
    public interface OnPick { void picked(TreeNode n); }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Theme th;
    private Skin skin;

    private TreeNode root;
    private final List<TreeNode> placed = new ArrayList<>();
    private OnPick onPick;
    private String emptyNote = "nothing scanned yet";

    public TreemapView(Context c, Theme theme, Skin s) {
        super(c);
        th = theme;
        skin = s;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, th.dpf(0.5f)));
        text.setTextSize(th.dpf(9));
        setClickable(true);
    }

    public void reskin(Skin s) { skin = s; invalidate(); }
    public void setOnPick(OnPick p) { onPick = p; }
    public void setNote(String s) { emptyNote = s; invalidate(); }

    public void setRoot(TreeNode n) {
        root = n;
        requestLayout();
        invalidate();
    }

    public TreeNode getRoot() { return root; }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(skin.windowFlat());
        c.drawRect(0, 0, w, h, fill);

        placed.clear();
        if (root == null || root.children == null || root.children.isEmpty()
                || root.size <= 0) {
            text.setColor(skin.inkSecondary);
            text.setTextSize(th.dpf(11));
            c.drawText(emptyNote, th.dpf(10), h / 2f, text);
            return;
        }

        // Layout is Squarify's job; this view only paints what comes back.
        List<TreeNode> kids = root.children;
        long[] sizes = new long[kids.size()];
        for (int i = 0; i < kids.size(); i++) sizes[i] = kids.get(i).size;
        float[][] rects = Squarify.layout(sizes, 0, 0, w, h);
        for (int i = 0; i < kids.size(); i++) {
            float[] r = rects[i];
            if (r[2] <= 0 || r[3] <= 0) continue;
            TreeNode n = kids.get(i);
            n.rx = r[0]; n.ry = r[1]; n.rw = r[2]; n.rh = r[3];
            placed.add(n);
        }

        for (TreeNode n : placed) {
            RectF r = new RectF(n.rx, n.ry, n.rx + n.rw, n.ry + n.rh);
            if (r.width() < 1.2f || r.height() < 1.2f) continue;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(colourFor(n));
            c.drawRect(r, fill);
            line.setColor(Skin.alpha(0xFF000000, 0.35f));
            c.drawRect(r, line);

            // Only label a rectangle with room for the text. Clipped labels are worse
            // than none: they turn a readable picture into visual noise.
            if (r.width() > th.dpf(52) && r.height() > th.dpf(16)) {
                text.setColor(Color.WHITE);
                text.setTextSize(th.dpf(9));
                String label = n.name;
                float max = r.width() - th.dpf(6);
                while (label.length() > 3 && text.measureText(label) > max) {
                    label = label.substring(0, label.length() - 2);
                }
                c.drawText(label, r.left + th.dpf(3), r.top + th.dpf(11), text);
                if (r.height() > th.dpf(30)) {
                    text.setTextSize(th.dpf(8));
                    text.setColor(Skin.alpha(Color.WHITE, 0.75f));
                    c.drawText(human(n.size), r.left + th.dpf(3), r.top + th.dpf(22), text);
                }
            }
        }
    }

    /**
     * Colour by kind, at every depth. A folder carries the kind of whatever fills it,
     * so the top level — where every child is a directory — is informative rather than
     * a wall of one colour. Folders are drawn a little flatter than files so the two
     * are still distinguishable.
     */
    private int colourFor(TreeNode n) {
        int base;
        switch (n.kind) {
            case Kind.IMAGE:   base = skin.accent(Skin.CH_FREQ);  break;
            case Kind.VIDEO:   base = skin.accent(Skin.CH_ALERT); break;
            case Kind.AUDIO:   base = skin.accent(Skin.CH_SAT);   break;
            case Kind.ARCHIVE: base = skin.accent(Skin.CH_RSRQ);  break;
            case Kind.DOC:     base = skin.accent(Skin.CH_RSRP);  break;
            default:           base = skin.inkSecondary;          break;
        }
        return Skin.alpha(base, n.hasChildren() ? 0.55f : 0.85f);
    }
    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i <= 0 || i == name.length() - 1 ? ""
                : name.substring(i + 1).toLowerCase(java.util.Locale.US);
    }

    public static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && onPick != null) {
            float x = e.getX(), y = e.getY();
            // Last placed wins, which is the one drawn on top.
            for (int i = placed.size() - 1; i >= 0; i--) {
                TreeNode n = placed.get(i);
                if (n.hit(x, y)) {
                    performClick();
                    onPick.picked(n);
                    return true;
                }
            }
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }
}

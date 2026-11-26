package com.boresight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A sized node in a directory tree.
 *
 * Deliberately free of Android types. It was an inner class of the treemap view, which
 * meant the tree-building code could not be exercised without a device — and tree
 * building is exactly the part that goes wrong quietly.
 */
public final class TreeNode {

    public String name = "";
    public String path = "";
    public long size;
    public boolean dir;
    public List<TreeNode> children = new ArrayList<>();

    /** Filled in by the layout pass; plain floats so this stays Android-free. */
    public float rx, ry, rw, rh;

    /** What kind of thing this is, for colour. See {@link Kind}. */
    public int kind = Kind.OTHER;

    public boolean hit(float x, float y) {
        return rw > 0 && rh > 0 && x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    public boolean hasChildren() { return children != null && !children.isEmpty(); }

    /** Largest first, recursively. */
    public void sortBySize() {
        if (children == null) return;
        Collections.sort(children, new Comparator<TreeNode>() {
            @Override public int compare(TreeNode a, TreeNode b) {
                return Long.compare(b.size, a.size);
            }
        });
    }

    /**
     * Sum leaf sizes upward.
     *
     * A node that turns out to have children takes their total rather than whatever
     * size it was given. That is what stops a directory's own reported size being
     * counted on top of the files inside it.
     */
    public long recompute() {
        if (!hasChildren()) return size;
        long t = 0;
        for (TreeNode k : children) t += k.recompute();
        dir = true;
        size = t;
        return t;
    }

    public void sortDeep(int depth) {
        if (depth > 24 || children == null) return;
        sortBySize();
        for (TreeNode k : children) k.sortDeep(depth + 1);
    }
}

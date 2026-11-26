package com.boresight;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns `du -ak` output into a tree.
 *
 * The one thing that matters here, and the thing that was wrong the first time: du
 * walks depth first, so a file's line arrives *before* the line for the directory
 * containing it. Verified against a real device —
 *
 *   4    /sdcard/Android/media/com.whatsapp/.../Sent/.nomedia
 *   160  /sdcard/Android/media/com.whatsapp/.../Sent/IMG-20220308-WA0002.jpg
 *   ...
 *   912  /sdcard/Android/media/com.whatsapp/.../Sent
 *
 * — so any parser that looks a parent up in a map it has been filling as it goes will
 * miss on every single line and flatten the whole subtree into the root. Parents are
 * therefore created on demand, and a directory's own line is ignored when the
 * directory already exists, because its children have already accounted for the bytes.
 *
 * Kept free of Android types so the parse can be tested without a phone.
 */
public final class DuTree {

    private DuTree() { }

    /**
     * @param base the directory du was pointed at; lines outside it are ignored
     * @param into the node representing {@code base}; children are added to it
     * @return total bytes, with nothing counted twice
     */
    public static long parse(String duOutput, String base, TreeNode into) {
        if (duOutput == null || base == null || into == null) return 0;

        Map<String, TreeNode> known = new HashMap<>();
        known.put(base, into);
        String prefix = base.endsWith("/") ? base : base + "/";

        for (String line : duOutput.split("\n")) {
            int tab = line.indexOf('\t');
            // Some builds separate with spaces rather than a tab.
            if (tab < 0) tab = firstSpace(line);
            if (tab <= 0) continue;

            long kb;
            try { kb = Long.parseLong(line.substring(0, tab).trim()); }
            catch (Exception e) { continue; }

            String path = line.substring(tab + 1).trim();
            if (path.isEmpty() || path.equals(base) || !path.startsWith(prefix)) continue;
            if (known.containsKey(path)) continue;   // already made as someone's parent

            int slash = path.lastIndexOf('/');
            TreeNode parent = ensureDir(known, base, into, path.substring(0, slash));

            TreeNode n = new TreeNode();
            n.name = path.substring(slash + 1);
            n.path = path;
            n.size = kb * 1024L;
            known.put(path, n);
            parent.children.add(n);
        }

        long total = into.recompute();
        into.sortDeep(0);
        return total;
    }

    private static int firstSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ' ') {
                // Skip any run of spaces; the path starts at the next non-space.
                int j = i;
                while (j < s.length() && s.charAt(j) == ' ') j++;
                return j - 1;
            }
        }
        return -1;
    }

    /** Find or build the node for a directory, creating intermediates as needed. */
    private static TreeNode ensureDir(Map<String, TreeNode> known, String base,
                                      TreeNode root, String path) {
        TreeNode have = known.get(path);
        if (have != null) return have;
        if (path.length() <= base.length() || !path.startsWith(base)) return root;

        int slash = path.lastIndexOf('/');
        if (slash < 0) return root;
        TreeNode parent = ensureDir(known, base, root, path.substring(0, slash));

        TreeNode n = new TreeNode();
        n.name = path.substring(slash + 1);
        n.path = path;
        n.dir = true;
        known.put(path, n);
        parent.children.add(n);
        return n;
    }

    /** How many nodes are in a tree, for sanity checks. */
    public static int count(TreeNode n) {
        int c = 1;
        if (n.children != null) for (TreeNode k : n.children) c += count(k);
        return c;
    }
}

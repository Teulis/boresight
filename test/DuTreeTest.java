import com.boresight.DuTree;
import com.boresight.TreeNode;

/**
 * Exercises the du parser against the ordering a real device produces: files before
 * the directory that holds them, with the directory's own total arriving last.
 */
public class DuTreeTest {

    static int failures = 0;

    public static void main(String[] args) {
        depthFirst();
        directoryLineNotDoubleCounted();
        deepNesting();
        junk();
        realSample();
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.printf("%-52s %s%s%n", what, ok ? "pass" : "FAIL",
                ok ? "" : "   <- " + detail);
        if (!ok) failures++;
    }

    static TreeNode root(String path) {
        TreeNode n = new TreeNode();
        n.path = path; n.name = path; n.dir = true;
        return n;
    }

    static TreeNode find(TreeNode n, String path) {
        if (path.equals(n.path)) return n;
        if (n.children != null) for (TreeNode k : n.children) {
            TreeNode r = find(k, path);
            if (r != null) return r;
        }
        return null;
    }

    /** The exact shape du emits: children first, parent last. */
    static void depthFirst() {
        System.out.println("-- depth-first ordering --");
        String du =
            "4\t/base/a/f1\n" +
            "8\t/base/a/f2\n" +
            "16\t/base/a\n" +
            "32\t/base/b/f3\n" +
            "36\t/base/b\n" +
            "56\t/base\n";
        TreeNode r = root("/base");
        long total = DuTree.parse(du, "/base", r);

        check("total is leaves only (4+8+32 KB)", total == (4 + 8 + 32) * 1024L,
                "got " + total);
        check("tree is not flattened", r.children.size() == 2,
                "root has " + r.children.size() + " children");
        TreeNode a = find(r, "/base/a");
        check("/base/a exists as a directory", a != null && a.dir, "");
        check("/base/a holds both its files",
                a != null && a.children.size() == 2, "");
        check("/base/a size is its leaves", a != null && a.size == 12 * 1024L,
                a == null ? "missing" : "got " + a.size);
        TreeNode f3 = find(r, "/base/b/f3");
        check("nested file reachable at full path", f3 != null && f3.size == 32768L, "");
    }

    static void directoryLineNotDoubleCounted() {
        System.out.println("\n-- directory lines --");
        String du =
            "100\t/base/d/big\n" +
            "104\t/base/d\n" +
            "108\t/base\n";
        TreeNode r = root("/base");
        long total = DuTree.parse(du, "/base", r);
        check("directory's own line ignored", total == 100 * 1024L, "got " + total);
        check("no phantom sibling node", DuTree.count(r) == 3,
                "node count " + DuTree.count(r));
    }

    static void deepNesting() {
        System.out.println("\n-- intermediate directories never listed --");
        // du always lists intermediates, but a truncated or filtered feed may not.
        String du = "20\t/base/x/y/z/file\n";
        TreeNode r = root("/base");
        long total = DuTree.parse(du, "/base", r);
        check("missing intermediates are created", find(r, "/base/x/y/z") != null, "");
        check("leaf attached in the right place",
                find(r, "/base/x/y/z/file") != null, "");
        check("total correct", total == 20 * 1024L, "got " + total);
    }

    static void junk() {
        System.out.println("\n-- malformed input --");
        TreeNode r = root("/base");
        long t = DuTree.parse("nonsense\n\n\tx\n999\n12\t/elsewhere/f\n", "/base", r);
        check("junk ignored, nothing outside base", t == 0, "got " + t);
        check("no children from junk", r.children.isEmpty(), "");
        check("null output is safe", DuTree.parse(null, "/base", root("/base")) == 0, "");
    }

    /** Real output shape captured from the S22, abbreviated. */
    static void realSample() {
        System.out.println("\n-- real device sample --");
        String base = "/sdcard/Android/media";
        String du =
            "4\t" + base + "/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/.nomedia\n" +
            "160\t" + base + "/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-20220308-WA0002.jpg\n" +
            "64\t" + base + "/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-20220308-WA0001.jpg\n" +
            "232\t" + base + "/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent\n" +
            "236\t" + base + "/com.whatsapp/WhatsApp/Media/WhatsApp Images\n" +
            "240\t" + base + "/com.whatsapp/WhatsApp/Media\n" +
            "244\t" + base + "/com.whatsapp/WhatsApp\n" +
            "248\t" + base + "/com.whatsapp\n" +
            "252\t" + base + "\n";
        TreeNode r = root(base);
        long total = DuTree.parse(du, base, r);
        check("total is the three real files", total == (4 + 160 + 64) * 1024L,
                "got " + total);
        check("one top-level package folder", r.children.size() == 1,
                "got " + r.children.size());
        TreeNode sent = find(r, base + "/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent");
        check("path with a space survives", sent != null, "");
        check("Sent holds three files", sent != null && sent.children.size() == 3,
                sent == null ? "missing" : "got " + sent.children.size());
        check("largest file sorted first",
                sent != null && sent.children.get(0).size == 160 * 1024L, "");
    }
}

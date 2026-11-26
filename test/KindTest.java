import com.boresight.Kind;
import com.boresight.TreeNode;

/**
 * Checks that a folder takes the colour of whatever actually fills it.
 *
 * This is by bytes, not by file count, and the difference matters: a folder holding
 * one 4 GB film and nine hundred thumbnails is a video folder, and colouring it green
 * because images win on count would be exactly the wrong answer.
 */
public class KindTest {

    static int failures = 0;

    public static void main(String[] args) {
        extensions();
        dominance();
        byBytesNotCount();
        nesting();
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.printf("%-52s %s%s%n", what, ok ? "pass" : "FAIL",
                ok ? "" : "   <- " + detail);
        if (!ok) failures++;
    }

    static TreeNode file(String name, long size) {
        TreeNode n = new TreeNode();
        n.name = name; n.path = "/x/" + name; n.size = size; n.dir = false;
        return n;
    }

    static TreeNode dir(String name, TreeNode... kids) {
        TreeNode n = new TreeNode();
        n.name = name; n.path = "/x/" + name; n.dir = true;
        for (TreeNode k : kids) n.children.add(k);
        return n;
    }

    static void extensions() {
        System.out.println("-- extension mapping --");
        check("jpg is an image",   Kind.of("a.jpg") == Kind.IMAGE, "");
        check("HEIC is an image",  Kind.of("A.HEIC") == Kind.IMAGE, "case");
        check("mkv is video",      Kind.of("a.mkv") == Kind.VIDEO, "");
        check("flac is audio",     Kind.of("a.flac") == Kind.AUDIO, "");
        check("obb is an archive", Kind.of("main.obb") == Kind.ARCHIVE, "");
        check("pdf is a document", Kind.of("a.pdf") == Kind.DOC, "");
        check("no extension is other", Kind.of("README") == Kind.OTHER, "");
        check("trailing dot is other", Kind.of("weird.") == Kind.OTHER, "");
        check("dotfile is other",  Kind.of(".nomedia") == Kind.OTHER, "");
    }

    static void dominance() {
        System.out.println("\n-- folder takes its dominant kind --");
        TreeNode d = dir("Movies",
                file("a.mp4", 5_000_000_000L),
                file("b.jpg", 1_000));
        d.recompute();
        Kind.assign(d);
        check("mostly-video folder is video", d.kind == Kind.VIDEO,
                "got " + Kind.name(d.kind));

        TreeNode p = dir("DCIM",
                file("1.jpg", 3_000_000),
                file("2.jpg", 3_000_000),
                file("clip.mp4", 1_000_000));
        p.recompute();
        Kind.assign(p);
        check("mostly-photo folder is images", p.kind == Kind.IMAGE,
                "got " + Kind.name(p.kind));
    }

    /** The case that makes counting wrong. */
    static void byBytesNotCount() {
        System.out.println("\n-- bytes beat counts --");
        TreeNode d = dir("Mixed");
        d.children.add(file("film.mkv", 4_000_000_000L));
        for (int i = 0; i < 900; i++) d.children.add(file("t" + i + ".jpg", 20_000));
        d.recompute();
        Kind.assign(d);
        check("900 thumbnails do not outvote one film",
                d.kind == Kind.VIDEO, "got " + Kind.name(d.kind)
                + " with " + d.children.size() + " children");
    }

    static void nesting() {
        System.out.println("\n-- nesting --");
        TreeNode inner = dir("Music", file("x.flac", 900_000_000L));
        TreeNode outer = dir("root", inner, file("note.txt", 10));
        outer.recompute();
        Kind.assign(outer);
        check("kind propagates up through folders",
                outer.kind == Kind.AUDIO, "got " + Kind.name(outer.kind));
        check("inner folder keeps its own kind",
                inner.kind == Kind.AUDIO, "got " + Kind.name(inner.kind));
        check("sizes still correct after assign",
                outer.size == 900_000_010L, "got " + outer.size);

        TreeNode empty = dir("empty");
        empty.recompute();
        Kind.assign(empty);
        check("empty folder does not throw", empty.kind == Kind.OTHER, "");
    }
}

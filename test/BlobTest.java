import com.boresight.Blob;
import com.boresight.Nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Tests for the format sniffer, the unwrap chain and the NBT reader.
 *
 * The failure that matters here is a confident wrong answer: calling an ordinary text
 * file base64 and decoding it into rubbish destroys the one view that would have been
 * useful. So the negative cases carry more weight than the positive ones.
 */
public class BlobTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        sniffsTheObviousFormats();
        doesNotCryBase64AtOrdinaryText();
        peelsAStack();
        stopsWhenItCannotUnwrap();
        readsNbt();
        survivesTruncatedNbt();

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("BlobTest ok");
    }

    private static void sniffsTheObviousFormats() throws Exception {
        System.out.println("\n-- sniff --");
        check("json", Blob.JSON.equals(Blob.sniff("{\"a\":1}".getBytes("UTF-8"))));
        check("json array", Blob.JSON.equals(Blob.sniff("  [1,2]".getBytes("UTF-8"))));
        check("xml", Blob.XML.equals(Blob.sniff("<?xml version=\"1.0\"?>".getBytes("UTF-8"))));
        check("gzip", Blob.GZIP.equals(Blob.sniff(gzip("hello world".getBytes("UTF-8")))));
        check("png", Blob.PNG.equals(Blob.sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10})));
        check("sqlite", Blob.SQLITE.equals(Blob.sniff(
                "SQLite format 3\0and then some".getBytes("UTF-8"))));
        check("nbt", Blob.NBT.equals(Blob.sniff(sampleNbt())));
        check("text", Blob.TEXT.equals(Blob.sniff(
                "just some ordinary notes, nothing special here at all".getBytes("UTF-8"))));
        check("empty", "empty".equals(Blob.sniff(new byte[0])));
        check("null", "empty".equals(Blob.sniff(null)));
    }

    /**
     * The dangerous false positive. Plain prose is made of base64 characters, so a
     * loose test turns a readable file into noise.
     */
    private static void doesNotCryBase64AtOrdinaryText() throws Exception {
        System.out.println("\n-- base64 is guessed carefully --");
        check("prose with spaces is text", !Blob.isBase64(
                "the quick brown fox jumps over the lazy dog again and again".getBytes("UTF-8")));
        check("a short word is not base64", !Blob.isBase64("abcd".getBytes("UTF-8")));
        // Minecraft's options.txt is exactly this shape, and every character in it is
        // a legal base64 character.
        check("key=value config is text", !Blob.isBase64(
                "width=1920\nheight=1080\nfullscreen=true\nvsync=false\n".getBytes("UTF-8")));
        check("a single key=value line is text",
                !Blob.isBase64("renderDistance=12andsomemorepaddingtextxx".getBytes("UTF-8")));
        String real = java.util.Base64.getEncoder().encodeToString(
                "a save file with enough content to be worth wrapping up".getBytes("UTF-8"));
        check("real base64 is spotted", Blob.isBase64(real.getBytes("UTF-8")));
    }

    private static void peelsAStack() throws Exception {
        System.out.println("\n-- peel --");
        byte[] json = "{\"coins\":9999,\"name\":\"vault\"}".getBytes("UTF-8");
        byte[] stacked = java.util.Base64.getEncoder()
                .encodeToString(gzip(json)).getBytes("UTF-8");

        List<Blob.Layer> layers = Blob.peel(stacked, 1 << 20);
        String chain = Blob.chain(layers);
        check("chain is base64 then gzip then JSON (" + chain + ")",
                chain.equals("base64  ->  gzip  ->  JSON"));
        byte[] last = layers.get(layers.size() - 1).bytes;
        check("the JSON comes out intact",
                new String(last, "UTF-8").contains("\"coins\":9999"));

        List<Blob.Layer> one = Blob.peel(json, 1 << 20);
        check("plain JSON is one layer", one.size() == 1);
        check("and is named JSON", Blob.JSON.equals(one.get(0).kind));
    }

    /** Encryption is not a wrapper. It must stop, not loop or lie. */
    private static void stopsWhenItCannotUnwrap() throws Exception {
        System.out.println("\n-- encrypted payloads --");
        byte[] cipher = new byte[512];
        java.util.Random r = new java.util.Random(7);
        r.nextBytes(cipher);
        byte[] wrapped = java.util.Base64.getEncoder()
                .encodeToString(cipher).getBytes("UTF-8");

        List<Blob.Layer> layers = Blob.peel(wrapped, 1 << 20);
        check("base64 comes off", Blob.BASE64.equals(layers.get(0).kind));
        check("and what is under it is not claimed to be anything ("
                + layers.get(layers.size() - 1).kind + ")",
                Blob.BINARY.equals(layers.get(layers.size() - 1).kind));
        check("it did not keep going forever", layers.size() <= 4);
    }

    private static void readsNbt() throws Exception {
        System.out.println("\n-- NBT --");
        String tree = Nbt.tree(sampleNbt());
        check("parsed at all", tree != null);
        check("names a compound", tree.contains("compound"));
        check("reads a string value (" + oneLine(tree) + ")",
                tree.contains("LevelName") && tree.contains("\"New World\""));
        check("reads an int", tree.contains("SpawnX") && tree.contains("128"));
        check("reads a nested compound", tree.contains("Player"));
        check("reads a list length", tree.contains("3 x double"));
        check("plain text is not NBT", Nbt.tree("hello there".getBytes("UTF-8")) == null);
        check("empty is not NBT", Nbt.tree(new byte[0]) == null);
    }

    private static void survivesTruncatedNbt() throws Exception {
        System.out.println("\n-- truncated NBT --");
        byte[] full = sampleNbt();
        byte[] cut = new byte[full.length / 2];
        System.arraycopy(full, 0, cut, 0, cut.length);
        String tree = Nbt.tree(cut);
        check("still returns what it read", tree != null && tree.contains("LevelName"));
        check("and says it was truncated", tree != null && tree.contains("truncated"));
    }

    // ------------------------------------------------------------------ fixtures

    /** A small level.dat-shaped tree: compound, string, int, nested compound, list. */
    private static byte[] sampleNbt() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeByte(Nbt.COMPOUND);
        writeName(o, "");                       // root

        o.writeByte(Nbt.STRING);
        writeName(o, "LevelName");
        writeName(o, "New World");

        o.writeByte(Nbt.INT);
        writeName(o, "SpawnX");
        o.writeInt(128);

        o.writeByte(Nbt.COMPOUND);
        writeName(o, "Player");
        o.writeByte(Nbt.LIST);
        writeName(o, "Pos");
        o.writeByte(Nbt.DOUBLE);
        o.writeInt(3);
        o.writeDouble(1.5);
        o.writeDouble(64.0);
        o.writeDouble(-2.25);
        o.writeByte(Nbt.END);                   // end of Player

        o.writeByte(Nbt.END);                   // end of root
        o.flush();
        return bytes.toByteArray();
    }

    private static void writeName(DataOutputStream o, String s) throws Exception {
        byte[] b = s.getBytes("UTF-8");
        o.writeShort(b.length);
        o.write(b);
    }

    private static byte[] gzip(byte[] in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(out);
        g.write(in);
        g.close();
        return out.toByteArray();
    }

    private static String oneLine(String s) {
        String t = s.replace('\n', ' ');
        return t.length() > 70 ? t.substring(0, 70) : t;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}

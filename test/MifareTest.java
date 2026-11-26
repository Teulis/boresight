import com.boresight.Mifare;

/**
 * Tests for the parts of MIFARE handling that are arithmetic rather than radio.
 *
 * The 4K geometry is the one that matters. Its first 32 sectors hold four blocks and
 * its last eight hold sixteen, so a loop written against a 1K card reads real bytes
 * from the wrong addresses on a 4K — no exception, no warning, just a dump that is
 * quietly wrong from sector 32 onward.
 */
public class MifareTest {

    private static int failures;

    public static void main(String[] args) {
        geometry();
        dictionary();
        dumpTextMarksWhatItCouldNotRead();
        hexRoundTrips();

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("MifareTest ok");
    }

    private static void geometry() {
        System.out.println("\n-- geometry --");
        check("1K has 16 sectors", Mifare.sectorsFor(1024) == 16);
        check("4K has 40 sectors", Mifare.sectorsFor(4096) == 40);
        check("Mini has 5 sectors", Mifare.sectorsFor(320) == 5);

        check("small sectors hold 4 blocks", Mifare.blocksInSector(0) == 4);
        check("sector 31 still holds 4", Mifare.blocksInSector(31) == 4);
        check("sector 32 holds 16", Mifare.blocksInSector(32) == 16);
        check("sector 39 holds 16", Mifare.blocksInSector(39) == 16);

        check("sector 0 starts at block 0", Mifare.firstBlock(0) == 0);
        check("sector 1 starts at block 4", Mifare.firstBlock(1) == 4);
        check("sector 31 starts at block 124", Mifare.firstBlock(31) == 124);
        check("sector 32 starts at block 128", Mifare.firstBlock(32) == 128);
        check("sector 33 starts at block 144", Mifare.firstBlock(33) == 144);

        // A 4K card is 256 blocks: 32*4 + 8*16.
        int total = 0;
        for (int s = 0; s < 40; s++) total += Mifare.blocksInSector(s);
        check("4K totals 256 blocks (got " + total + ")", total == 256);

        check("trailer of a small sector is block 3", Mifare.isTrailer(0, 3));
        check("block 2 is not a trailer", !Mifare.isTrailer(0, 2));
        check("trailer of a big sector is block 15", Mifare.isTrailer(32, 15));
        check("block 3 of a big sector is not a trailer", !Mifare.isTrailer(32, 3));
    }

    private static void dictionary() {
        System.out.println("\n-- dictionary --");
        check("the factory default comes first",
                "FFFFFFFFFFFF".equals(Mifare.dictionary(null).get(0)));
        int builtIn = Mifare.dictionary(null).size();
        check("has a useful number of keys (" + builtIn + ")", builtIn > 20);

        check("user keys are added",
                Mifare.dictionary("112233445566").contains("112233445566"));
        check("lower case is accepted",
                Mifare.dictionary("aabbccddee00").contains("AABBCCDDEE00"));
        check("colons are tolerated",
                Mifare.dictionary("11:22:33:44:55:66").contains("112233445566"));
        check("comments are ignored",
                Mifare.dictionary("# a note\n112233445566").size() == builtIn + 1);
        check("a duplicate of a built-in does not grow the list",
                Mifare.dictionary("FFFFFFFFFFFF").size() == builtIn);
        check("a short key is rejected",
                Mifare.dictionary("1122").size() == builtIn);
        check("a non-hex key is rejected",
                Mifare.dictionary("ZZZZZZZZZZZZ").size() == builtIn);

        byte[] k = Mifare.keyBytes("A0A1A2A3A4A5");
        check("key bytes are right",
                k.length == 6 && (k[0] & 0xFF) == 0xA0 && (k[5] & 0xFF) == 0xA5);
    }

    /** A missing sector must look missing, not like a card that was shorter. */
    private static void dumpTextMarksWhatItCouldNotRead() {
        System.out.println("\n-- dump text --");
        byte[][] blocks = new byte[8][];
        for (int i = 0; i < 4; i++) {
            blocks[i] = new byte[16];
            blocks[i][0] = (byte) i;
        }
        // Sector 1 was never read.
        String[] keys = { "FFFFFFFFFFFF", null };
        String txt = Mifare.toText(blocks, keys, 2);

        check("names both sectors",
                txt.contains("+Sector: 0") && txt.contains("+Sector: 1"));
        check("shows the key that opened sector 0", txt.contains("key FFFFFFFFFFFF"));
        check("marks the unread blocks", txt.contains("--------------------------------"));
        int lines = txt.split("\n").length;
        check("one line per block plus a header each (got " + lines + ")", lines == 10);
    }

    private static void hexRoundTrips() {
        System.out.println("\n-- hex --");
        byte[] d = {0x00, (byte) 0xAB, 0x10, (byte) 0xFF};
        check("hex", "00AB10FF".equals(Mifare.hex(d)));
        check("spaced hex groups", "00 AB 10 FF".equals(Mifare.hexSpaced(d)));
        check("parses back", java.util.Arrays.equals(d, Mifare.parseHex("00AB10FF")));
        check("parses spaced", java.util.Arrays.equals(d, Mifare.parseHex("00 ab 10 ff")));
        check("ascii shows printables",
                "..A.".equals(Mifare.ascii(new byte[]{0, 1, 'A', (byte) 0x90})));
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}

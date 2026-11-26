package com.boresight;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MIFARE Classic facts that are pure data: key dictionary, sector geometry, dump format.
 *
 * Kept apart from the Android NFC classes so the parts that are easy to get wrong can be
 * tested on a desktop. The geometry in particular: a 4K card is not simply a bigger 1K
 * card — its first 32 sectors hold 4 blocks each and its last 8 hold 16, so a loop that
 * assumes four blocks per sector reads the right bytes from the wrong places on exactly
 * the cards most worth reading.
 */
public final class Mifare {

    private Mifare() { }

    /**
     * The well-known keys, in the order worth trying them.
     *
     * The factory default first, because a blank or lightly used card answers to it
     * immediately; then the keys published in NXP's own application notes and the ones
     * that have been in every public dictionary for twenty years. This is a dictionary,
     * not an attack: it opens cards whose owners never changed the key, which is most of
     * the hotel, gym and office cards in circulation, and it opens nothing else.
     */
    public static final String[] KEYS = {
        "FFFFFFFFFFFF",   // factory default
        "A0A1A2A3A4A5",   // NXP application note / MAD sector key A
        "D3F7D3F7D3F7",   // NDEF public key
        "000000000000",
        "B0B1B2B3B4B5",
        "4D3A99C351DD",
        "1A982C7E459A",
        "AABBCCDDEEFF",
        "714C5C886E97",
        "587EE5F9350F",
        "A0478CC39091",
        "533CB6C723F6",
        "8FD0A4F256E9",
        "A64598A77478",
        "26940B21FF5D",
        "FC00018778F7",
        "00000FFE2488",
        "5C598C9C58B5",
        "E4D2770A89BE",
        "434F4D4D4F41",
        "434F4D4D4F42",
        "47524F555041",
        "47524F555042",
        "505244204D41",
        "505244204D42",
        "0297927C0F77",
        "EE0042F88840",
        "722BFCC5375F",
        "F1D83F964314",
        "54726176656C",
        "776974687573",
        "4AF9D7ADEBE4",
        "2BA9621E0A36",
    };

    /** Keys the user has added, one per line, plus the built-ins. Duplicates removed. */
    public static List<String> dictionary(String extra) {
        Set<String> all = new LinkedHashSet<>();
        for (String k : KEYS) all.add(k.toUpperCase(Locale.US));
        if (extra != null) {
            for (String line : extra.split("\\r?\\n")) {
                String k = line.trim().replace(":", "").replace(" ", "")
                                .toUpperCase(Locale.US);
                if (k.isEmpty() || k.startsWith("#")) continue;
                if (k.length() == 12 && k.matches("[0-9A-F]+")) all.add(k);
            }
        }
        return new ArrayList<>(all);
    }

    public static byte[] keyBytes(String hex) {
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ------------------------------------------------------------------ geometry

    /**
     * How many blocks a sector holds.
     *
     * 1K and the first 32 sectors of a 4K hold four; the last eight sectors of a 4K hold
     * sixteen. Getting this wrong does not throw, it just reads the wrong card.
     */
    public static int blocksInSector(int sector) {
        return sector < 32 ? 4 : 16;
    }

    /** The absolute block number a sector starts at. */
    public static int firstBlock(int sector) {
        return sector < 32 ? sector * 4 : 128 + (sector - 32) * 16;
    }

    /** Total sectors for a card of this many bytes. */
    public static int sectorsFor(int sizeBytes) {
        switch (sizeBytes) {
            case 320:  return 5;     // Mini
            case 1024: return 16;    // 1K
            case 2048: return 32;    // 2K
            case 4096: return 40;    // 4K
            default:   return Math.max(1, sizeBytes / 64);
        }
    }

    /** The trailer is the last block of its sector and holds the keys, not data. */
    public static boolean isTrailer(int sector, int blockInSector) {
        return blockInSector == blocksInSector(sector) - 1;
    }

    // ------------------------------------------------------------------ dump text

    /**
     * The dump in the shape every other MIFARE tool writes it: one line of hex per
     * block, sectors separated, unreadable sectors marked rather than skipped.
     *
     * Marked rather than skipped on purpose. A dump with a sector silently missing looks
     * like a complete card that happens to be short, and whoever reads it later has no
     * way to tell a card that had no sector 12 from a key that was not in the dictionary.
     */
    public static String toText(byte[][] blocks, String[] keyUsed, int sectors) {
        StringBuilder b = new StringBuilder();
        int block = 0;
        for (int s = 0; s < sectors; s++) {
            b.append("+Sector: ").append(s);
            if (keyUsed != null && s < keyUsed.length && keyUsed[s] != null) {
                b.append("   key ").append(keyUsed[s]);
            }
            b.append('\n');
            for (int i = 0; i < blocksInSector(s); i++, block++) {
                byte[] data = blocks != null && block < blocks.length ? blocks[block] : null;
                b.append(data == null ? "--------------------------------" : hex(data));
                b.append('\n');
            }
        }
        return b.toString();
    }

    public static String hex(byte[] d) {
        StringBuilder b = new StringBuilder(d.length * 2);
        for (byte x : d) b.append(String.format(Locale.US, "%02X", x));
        return b.toString();
    }

    /** Spaced hex, for reading on a phone screen rather than feeding to another tool. */
    public static String hexSpaced(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) b.append(i % 4 == 0 ? "  " : " ");
            b.append(String.format(Locale.US, "%02X", d[i]));
        }
        return b.toString();
    }

    public static byte[] parseHex(String s) {
        String clean = s == null ? "" : s.replaceAll("[^0-9a-fA-F]", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Printable ASCII beside the hex, which is how a name or a URL in a block shows up. */
    public static String ascii(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) {
            int v = x & 0xFF;
            b.append(v >= 32 && v < 127 ? (char) v : '.');
        }
        return b.toString();
    }
}

package com.boresight;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Work out what a file actually is, and unwrap it until something readable falls out.
 *
 * A save file is usually not one format but a stack of them: JSON that was gzipped and
 * then base64'd, or NBT inside gzip. Opening any of those in a text or hex viewer shows
 * the outermost layer and nothing else, which is why so much of a phone's data looks
 * like noise when none of it is.
 *
 * So: sniff, unwrap, sniff again, up to a few layers, and report the chain that got
 * there. The chain matters as much as the result — "base64 → gzip → NBT" tells you what
 * the file is, and "base64 → 155 KB of nothing recognisable" tells you it is encrypted
 * rather than merely wrapped.
 *
 * This decodes formats. It does not break encryption: a file that is still unreadable
 * after the wrappers come off is reported as such, with its size, and left alone.
 */
public final class Blob {

    private Blob() { }

    public static final String GZIP = "gzip", ZLIB = "zlib", BASE64 = "base64",
            JSON = "JSON", XML = "XML", NBT = "NBT", SQLITE = "SQLite", ZIP = "zip",
            PNG = "PNG", TEXT = "text", BINARY = "unrecognised bytes";

    /** What a run of bytes looks like, without unwrapping anything. */
    public static String sniff(byte[] b) {
        if (b == null || b.length == 0) return "empty";
        if (starts(b, 0x1F, 0x8B)) return GZIP;
        if (starts(b, 0x50, 0x4B, 0x03, 0x04)) return ZIP;
        if (starts(b, 0x89, 'P', 'N', 'G')) return PNG;
        if (b.length >= 16 && new String(b, 0, 15, java.nio.charset.Charset.forName("ISO-8859-1"))
                .startsWith("SQLite format 3")) return SQLITE;
        // zlib: CMF/FLG where CMF low nibble is 8 (deflate) and the pair is a multiple
        // of 31. Checked after gzip, which also starts with a deflate stream inside.
        if (b.length >= 2 && (b[0] & 0x0F) == 8
                && ((((b[0] & 0xFF) << 8) | (b[1] & 0xFF)) % 31) == 0) return ZLIB;
        if (Nbt.looksLike(b)) return NBT;

        String head = head(b, 400);
        String t = head.trim();
        if (t.startsWith("{") || t.startsWith("[")) return JSON;
        if (t.startsWith("<")) return XML;
        if (isBase64(b)) return BASE64;
        if (isMostlyText(b)) return TEXT;
        return BINARY;
    }

    /** One unwrapping. */
    public static class Layer {
        public final String kind;
        public final byte[] bytes;
        Layer(String kind, byte[] bytes) { this.kind = kind; this.bytes = bytes; }
    }

    /**
     * Peel wrappers until what is left is content rather than packaging.
     *
     * @return the layers in order, the last of which is whatever could not be unwrapped
     */
    public static List<Layer> peel(byte[] data, int limit) {
        List<Layer> out = new ArrayList<>();
        byte[] cur = data;
        for (int i = 0; i < 4 && cur != null; i++) {
            String kind = sniff(cur);
            out.add(new Layer(kind, cur));
            byte[] next;
            if (GZIP.equals(kind))        next = gunzip(cur, limit);
            else if (ZLIB.equals(kind))   next = inflate(cur, limit, false);
            else if (BASE64.equals(kind)) next = unbase64(cur);
            else                          next = null;
            // A decode that produces nothing, or the same thing, is not progress.
            if (next == null || next.length == 0 || next.length == cur.length
                    && java.util.Arrays.equals(next, cur)) break;
            cur = next;
        }
        return out;
    }

    /** "base64 -> gzip -> NBT", for the header of a viewer. */
    public static String chain(List<Layer> layers) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < layers.size(); i++) {
            if (i > 0) b.append("  ->  ");
            b.append(layers.get(i).kind);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ decoders

    public static byte[] gunzip(byte[] in, int limit) {
        java.io.InputStream g = null;
        try {
            g = new GZIPInputStream(new java.io.ByteArrayInputStream(in));
            return drain(g, limit);
        } catch (Throwable t) {
            return null;
        } finally {
            if (g != null) try { g.close(); } catch (Throwable ignored) { }
        }
    }

    public static byte[] inflate(byte[] in, int limit, boolean raw) {
        Inflater inf = new Inflater(raw);
        try {
            inf.setInput(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            while (!inf.finished() && out.size() < limit) {
                int n = inf.inflate(buf);
                if (n == 0) break;
                out.write(buf, 0, n);
            }
            return out.size() == 0 ? null : out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            inf.end();
        }
    }

    /**
     * Decode base64, tolerating the whitespace and BOM that real files carry.
     *
     * Android's Base64 is strict about both, and a save file written by a game engine
     * routinely has a BOM in front and newlines through the middle.
     */
    public static byte[] unbase64(byte[] in) {
        StringBuilder clean = new StringBuilder(in.length);
        for (int i = 0; i < in.length; i++) {
            char c = (char) (in[i] & 0xFF);
            if (isB64Char(c)) clean.append(c);
            else if (c == '\r' || c == '\n' || c == ' ' || c == '\t') continue;
            else if (i < 3) continue;            // BOM bytes
            else return null;                    // something that is not base64 at all
        }
        try {
            // java.util.Base64 rather than android.util.Base64: it exists from API 26,
            // this app needs 29, and it is the version that also runs in the desktop
            // tests. MIME decoding ignores anything it does not recognise, which is
            // what a file written by a game engine needs.
            return java.util.Base64.getMimeDecoder().decode(clean.toString());
        } catch (Throwable t) {
            try {
                return java.util.Base64.getUrlDecoder().decode(clean.toString());
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] drain(java.io.InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while (out.size() < limit && (n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xFF) != (sig[i] & 0xFF)) return false;
        return true;
    }

    static String head(byte[] b, int n) {
        int len = Math.min(n, b.length);
        try { return new String(b, 0, len, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static boolean isB64Char(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
    }

    /**
     * Base64 is guessed, not proved. The test is deliberately strict — a long run of
     * nothing but base64 characters — because calling an ordinary text file base64 and
     * decoding it to rubbish is worse than leaving it as text.
     */
    public static boolean isBase64(byte[] b) {
        int start = 0;
        if (b.length < 32) return false;
        // Skip a BOM.
        if (b.length > 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB
                && (b[2] & 0xFF) == 0xBF) start = 3;

        int seen = 0, pad = 0;
        boolean whole = b.length <= 4096;
        int n = Math.min(b.length, 4096);
        for (int i = start; i < n; i++) {
            char c = (char) (b[i] & 0xFF);
            if (c == '\r' || c == '\n') continue;
            if (!isB64Char(c)) return false;
            // '=' is padding and can only ever be the last one or two characters.
            // Without this, "width=1920\nheight=1080" is made entirely of base64
            // characters and a config file gets decoded into noise.
            if (c == '=') { pad++; if (pad > 2) return false; }
            else if (pad > 0) return false;
            seen++;
        }
        // A whole base64 document encodes whole bytes, so its length is a multiple of
        // four. Only checked when the whole file was examined.
        if (whole && seen % 4 != 0) return false;
        return seen >= 32;
    }

    private static boolean isMostlyText(byte[] b) {
        int n = Math.min(b.length, 2048), printable = 0, counted = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) return false;
            counted++;
            if (c >= 0x20 && c < 0x7F) printable++;
            else if (c == '\n' || c == '\r' || c == '\t') printable++;
            else if (c >= 0xC2) printable++;        // plausible UTF-8 lead byte
        }
        return counted > 0 && printable * 10 >= counted * 9;
    }
}

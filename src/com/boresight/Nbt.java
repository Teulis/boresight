package com.boresight;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Minecraft's Named Binary Tag format, rendered as an indented tree.
 *
 * level.dat, servers.dat, player files and most of what a Minecraft world keeps are NBT:
 * a big-endian, self-describing tree of named tags, usually gzipped. A hex viewer shows
 * it as noise even though every byte of it is labelled.
 *
 * Written to keep reading a damaged file rather than to validate one. A save that was
 * cut short mid-write is exactly the one worth looking at, so a truncated stream ends
 * the tree with a note instead of throwing the whole parse away. Arrays and lists are
 * capped per node: a chunk's block array is a megabyte of numbers that nobody reads by
 * scrolling, and the count is the useful part.
 */
public final class Nbt {

    private Nbt() { }

    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5,
            DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10,
            INT_ARRAY = 11, LONG_ARRAY = 12;

    /** Elements of any one array or list that are printed before it is summarised. */
    private static final int ELEMENT_CAP = 24;
    private static final int DEPTH_CAP = 48;

    /**
     * True when these bytes open like uncompressed NBT.
     *
     * A root tag is a compound, so the first byte is 10 and the two after it are the
     * length of its name — nearly always zero. Cheap and wrong occasionally, which is
     * why the caller offers it rather than insisting on it.
     */
    public static boolean looksLike(byte[] b) {
        if (b == null || b.length < 3) return false;
        if (b[0] != COMPOUND) return false;
        int nameLen = ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
        return nameLen >= 0 && nameLen < 128 && 3 + nameLen <= b.length;
    }

    /** Parse to an indented tree, or return null when this is not NBT at all. */
    public static String tree(byte[] data) {
        if (data == null || data.length == 0) return null;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        StringBuilder out = new StringBuilder();
        try {
            int type = in.readUnsignedByte();
            if (type != COMPOUND) return null;
            String name = readString(in);
            out.append(typeName(COMPOUND)).append(name.isEmpty() ? "" : " \"" + name + "\"")
               .append('\n');
            readPayload(in, COMPOUND, out, 1);
        } catch (EOFException e) {
            out.append("\n-- the file ends here; it was truncated --");
        } catch (Exception e) {
            if (out.length() == 0) return null;
            out.append("\n-- stopped: ").append(e.getClass().getSimpleName())
               .append(' ').append(String.valueOf(e.getMessage())).append(" --");
        }
        return out.toString();
    }

    private static void readPayload(DataInputStream in, int type, StringBuilder out,
                                    int depth) throws IOException {
        if (depth > DEPTH_CAP) { out.append(pad(depth)).append("...\n"); return; }
        switch (type) {
            case COMPOUND:
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == END) return;
                    String n = readString(in);
                    out.append(pad(depth)).append(n);
                    if (isScalar(t)) {
                        out.append(" = ");
                        readPayload(in, t, out, depth + 1);
                    } else {
                        out.append("  (").append(typeName(t)).append(")\n");
                        readPayload(in, t, out, depth + 1);
                    }
                }
            case LIST: {
                int t = in.readUnsignedByte();
                int n = in.readInt();
                out.append(pad(depth)).append(n).append(" x ").append(typeName(t))
                   .append('\n');
                for (int i = 0; i < n; i++) {
                    if (i >= ELEMENT_CAP) {
                        out.append(pad(depth)).append("... ").append(n - ELEMENT_CAP)
                           .append(" more\n");
                        skipList(in, t, n - ELEMENT_CAP);
                        break;
                    }
                    if (isScalar(t)) {
                        out.append(pad(depth + 1)).append('[').append(i).append("] = ");
                        readPayload(in, t, out, depth + 2);
                    } else {
                        out.append(pad(depth + 1)).append('[').append(i).append(']')
                           .append("  (").append(typeName(t)).append(")\n");
                        readPayload(in, t, out, depth + 2);
                    }
                }
                return;
            }
            case BYTE:   out.append(in.readByte()).append('\n'); return;
            case SHORT:  out.append(in.readShort()).append('\n'); return;
            case INT:    out.append(in.readInt()).append('\n'); return;
            case LONG:   out.append(in.readLong()).append('\n'); return;
            case FLOAT:  out.append(in.readFloat()).append('\n'); return;
            case DOUBLE: out.append(in.readDouble()).append('\n'); return;
            case STRING: out.append('"').append(readString(in)).append("\"\n"); return;
            case BYTE_ARRAY: {
                int n = in.readInt();
                out.append(numbers(in, n, 1)).append('\n');
                return;
            }
            case INT_ARRAY: {
                int n = in.readInt();
                out.append(numbers(in, n, 4)).append('\n');
                return;
            }
            case LONG_ARRAY: {
                int n = in.readInt();
                out.append(numbers(in, n, 8)).append('\n');
                return;
            }
            default:
                throw new IOException("unknown tag type " + type);
        }
    }

    /** A capped preview of a numeric array, with the full count in front of it. */
    private static String numbers(DataInputStream in, int n, int width) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append(n).append(width == 1 ? " bytes" : width == 4 ? " ints" : " longs")
         .append(n == 0 ? "" : ":");
        for (int i = 0; i < n; i++) {
            long v = width == 1 ? in.readByte() : width == 4 ? in.readInt() : in.readLong();
            if (i < ELEMENT_CAP) b.append(' ').append(v);
            else if (i == ELEMENT_CAP) b.append(" ...");
        }
        return b.toString();
    }

    private static void skipList(DataInputStream in, int type, int count)
            throws IOException {
        StringBuilder sink = new StringBuilder();
        for (int i = 0; i < count; i++) readPayload(in, type, sink, DEPTH_CAP + 1);
    }

    private static boolean isScalar(int t) {
        return t == BYTE || t == SHORT || t == INT || t == LONG || t == FLOAT
            || t == DOUBLE || t == STRING || t == BYTE_ARRAY || t == INT_ARRAY
            || t == LONG_ARRAY;
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, "UTF-8");
    }

    private static String pad(int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("  ");
        return b.toString();
    }

    public static String typeName(int t) {
        switch (t) {
            case END: return "end";
            case BYTE: return "byte";
            case SHORT: return "short";
            case INT: return "int";
            case LONG: return "long";
            case FLOAT: return "float";
            case DOUBLE: return "double";
            case BYTE_ARRAY: return "bytes";
            case STRING: return "string";
            case LIST: return "list";
            case COMPOUND: return "compound";
            case INT_ARRAY: return "ints";
            case LONG_ARRAY: return "longs";
            default: return "type " + t;
        }
    }
}

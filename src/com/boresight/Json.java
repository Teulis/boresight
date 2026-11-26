package com.boresight;

/**
 * Make JSON readable without reordering it.
 *
 * Game saves, config blobs and API dumps arrive minified onto one line, which the text
 * viewer renders as a single unreadable stripe. This lays them out.
 *
 * Hand-written rather than routed through org.json, for one reason that matters when the
 * point is to inspect a file: JSONObject is backed by a HashMap, so parsing and
 * re-emitting silently reorders every key. A save file's key order is information — it
 * is the order the game wrote them — and a viewer that quietly shuffles it is lying
 * about the file. This only ever inserts whitespace between tokens, so what comes out is
 * the input with newlines in it and nothing else changed.
 *
 * It is also forgiving by design. A truncated or slightly malformed file is exactly when
 * you most want to look at it, so there is no parse step that can refuse: anything it
 * does not understand is passed through untouched.
 */
public final class Json {

    private Json() { }

    /** True when this looks like JSON worth offering to format. */
    public static boolean looksLike(String s) {
        if (s == null) return false;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return false;
        char c = s.charAt(i);
        return c == '{' || c == '[';
    }

    /**
     * Is it already laid out? A file with newlines every few tokens needs nothing done
     * to it, and reformatting it would only churn whatever style it came in.
     */
    public static boolean alreadyIndented(String s) {
        if (s == null || s.isEmpty()) return true;
        int lines = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        return s.length() / lines < 200;
    }

    public static String pretty(String src) { return pretty(src, 2); }

    public static String pretty(String src, int indent) {
        if (src == null) return "";
        StringBuilder out = new StringBuilder(src.length() + src.length() / 4);
        int depth = 0;
        boolean inString = false, escaped = false;

        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);

            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }

            switch (c) {
                case '"':
                    inString = true;
                    out.append(c);
                    break;
                case '{':
                case '[':
                    out.append(c);
                    // An empty object or array stays on one line: "{}" reads better
                    // than two lines saying nothing.
                    if (closesImmediately(src, i)) {
                        out.append(src.charAt(nextMeaningful(src, i + 1)));
                        i = nextMeaningful(src, i + 1);
                    } else {
                        depth++;
                        newline(out, depth, indent);
                    }
                    break;
                case '}':
                case ']':
                    depth = Math.max(0, depth - 1);
                    newline(out, depth, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    newline(out, depth, indent);
                    break;
                case ':':
                    out.append(": ");
                    break;
                default:
                    // Whitespace between tokens is dropped; this supplies its own.
                    if (!Character.isWhitespace(c)) out.append(c);
            }
        }
        return out.toString();
    }

    private static void newline(StringBuilder out, int depth, int indent) {
        out.append('\n');
        for (int i = 0; i < depth * indent; i++) out.append(' ');
    }

    private static int nextMeaningful(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static boolean closesImmediately(String s, int open) {
        int i = nextMeaningful(s, open + 1);
        if (i >= s.length()) return false;
        char c = s.charAt(i);
        return (s.charAt(open) == '{' && c == '}') || (s.charAt(open) == '[' && c == ']');
    }

    /**
     * A one-line summary of the shape, for the top of the viewer: how deep it goes and
     * how many keys are at the root. Enough to know what you are looking at before
     * scrolling.
     */
    public static String describe(String src) {
        if (src == null) return "";
        int depth = 0, max = 0, rootItems = 0;
        boolean inString = false, escaped = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{' || c == '[') { depth++; max = Math.max(max, depth); }
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 1) rootItems++;
        }
        if (max == 0) return "not JSON";
        return (rootItems + 1) + (rootItems == 0 ? " item" : " items") + " at the root"
             + "  ·  " + max + " deep"
             + (depth != 0 ? "  ·  unbalanced, the file may be truncated" : "");
    }
}

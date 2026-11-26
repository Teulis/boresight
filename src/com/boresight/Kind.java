package com.boresight;

/**
 * What a file is, for the storage map's colouring.
 *
 * A folder takes the kind that accounts for most of its bytes. Without that, the top
 * level of a treemap is a wall of one colour — every child is a directory — and the
 * legend promises something the picture does not deliver. Colouring by dominant
 * content is what makes "that big green block is photos" true at every depth.
 */
public final class Kind {

    public static final int OTHER   = 0;
    public static final int IMAGE   = 1;
    public static final int VIDEO   = 2;
    public static final int AUDIO   = 3;
    public static final int ARCHIVE = 4;
    public static final int DOC     = 5;

    private Kind() { }

    public static int of(String name) {
        int i = name.lastIndexOf('.');
        if (i <= 0 || i == name.length() - 1) return OTHER;
        String e = name.substring(i + 1).toLowerCase(java.util.Locale.US);
        switch (e) {
            case "jpg": case "jpeg": case "png": case "heic": case "heif":
            case "webp": case "gif": case "bmp": case "dng": case "raw":
                return IMAGE;
            case "mp4": case "mkv": case "mov": case "avi": case "webm":
            case "m4v": case "3gp": case "ts": case "insv":
                return VIDEO;
            case "mp3": case "flac": case "wav": case "ogg": case "m4a":
            case "aac": case "opus": case "wma":
                return AUDIO;
            case "apk": case "obb": case "zip": case "gz": case "xz":
            case "7z": case "rar": case "tar": case "bz2": case "img":
                return ARCHIVE;
            case "db": case "sqlite": case "json": case "xml": case "csv":
            case "txt": case "log": case "pdf": case "md": case "html":
                return DOC;
            default:
                return OTHER;
        }
    }

    /**
     * Assign a kind to every node, bottom up.
     *
     * A directory becomes whichever kind holds the most bytes beneath it, so the
     * colour at any level answers "what is this mostly made of".
     */
    public static int assign(TreeNode n) {
        if (!n.hasChildren()) {
            n.kind = n.dir ? OTHER : of(n.name);
            return n.kind;
        }
        long[] bytes = new long[6];
        for (TreeNode k : n.children) {
            int kk = assign(k);
            if (kk >= 0 && kk < bytes.length) bytes[kk] += k.size;
        }
        int best = OTHER;
        long most = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] > most) { most = bytes[i]; best = i; }
        }
        n.kind = best;
        return best;
    }

    public static String name(int k) {
        switch (k) {
            case IMAGE:   return "images";
            case VIDEO:   return "video";
            case AUDIO:   return "audio";
            case ARCHIVE: return "archives and apks";
            case DOC:     return "documents";
            default:      return "other";
        }
    }
}

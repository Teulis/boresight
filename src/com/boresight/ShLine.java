package com.boresight;

/**
 * Whether a typed line is a finished shell command.
 *
 * Its own class, with no dependencies on anything Android, so it can be tested on a
 * desktop. That matters more than it sounds: the failure this prevents is invisible.
 *
 * Every command the console sends is followed down the same pipe by a marker line
 * carrying the exit status and the working directory. If the user's line is not
 * finished — it ends in a backslash, or opens a quote it never closes — the shell
 * treats that marker as a continuation of the command and swallows it. Nothing throws.
 * The command runs. The screen then waits forever for a completion that was consumed,
 * and the marker text appears as literal output. It does not look like a bug; it looks
 * like the shell hung.
 *
 * Found by typing a trailing backslash at the prompt.
 */
public final class ShLine {

    private ShLine() { }

    /**
     * True when the line can be sent, false when the prompt should stay open.
     *
     * Covers the two cases that actually occur at a prompt: an escaping backslash at
     * the end, and an unclosed single or double quote. Here-documents are not tracked
     * and would hang a session the same way, which is why the console offers a restart
     * and says plainly what it is for.
     */
    public static boolean complete(String s) {
        if (s == null) return true;
        boolean inSingle = false, inDouble = false, escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            // Inside single quotes a backslash is an ordinary character: it escapes
            // nothing and cannot continue the line. Treating it as an escape would
            // hold the prompt open on a command that was already finished, which is
            // the opposite failure and just as irritating.
            if (c == '\\' && !inSingle) { escaped = true; continue; }
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
        }
        // A trailing backslash leaves `escaped` set: the line continues.
        return !escaped && !inSingle && !inDouble;
    }
}

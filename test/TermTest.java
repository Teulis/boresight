import com.boresight.ShLine;

/**
 * Tests for the terminal's line-continuation check.
 *
 * Worth testing on a desktop because the failure is invisible and permanent. Every
 * command the console sends is followed down the same pipe by a marker line carrying
 * the exit status; if the user's line is unfinished, the shell eats the marker as a
 * continuation. Nothing throws. The command runs. The screen simply waits forever for
 * a completion that was consumed, and the marker text turns up as output. Getting this
 * wrong does not look like a bug, it looks like the shell hung.
 */
public class TermTest {

    private static int failures;

    public static void main(String[] args) {
        finishedLines();
        unfinishedLines();
        quotingSubtleties();
        edges();

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("TermTest ok");
    }

    /** Ordinary commands must go straight through. */
    private static void finishedLines() {
        System.out.println("\n-- complete --");
        String[] ok = {
            "ls",
            "cd /sdcard",
            "echo hello",
            "echo 'single quoted'",
            "echo \"double quoted\"",
            "grep -i -E 'model|fingerprint' /x",
            "echo \"it's fine\"",              // apostrophe inside double quotes
            "echo 'say \"hi\"'",               // double quotes inside single
            "echo a\\\\b",                     // escaped backslash, line ends complete
            "find / -name '*.json' -print",
            "",
        };
        for (String s : ok) check("complete: " + show(s), ShLine.complete(s));
    }

    /** These must hold the prompt open instead of sending. */
    private static void unfinishedLines() {
        System.out.println("\n-- continues --");
        String[] more = {
            "echo \\",                         // the case that found this bug
            "ls -la \\",
            "echo 'unterminated",
            "echo \"unterminated",
            "echo 'one' 'two",                 // second quote opens and never closes
            "echo \"a\" \"b",
        };
        for (String s : more) check("continues: " + show(s), !ShLine.complete(s));
    }

    /**
     * The cases where the two quote styles interact.
     *
     * A backslash inside single quotes is a literal character in the shell, so it must
     * not be treated as an escape — getting this wrong would hold the prompt open on a
     * perfectly finished command, which is the opposite failure and just as annoying.
     */
    private static void quotingSubtleties() {
        System.out.println("\n-- quoting --");
        check("backslash inside single quotes is literal",
                ShLine.complete("echo 'a\\'"));
        check("backslash escapes inside double quotes",
                !ShLine.complete("echo \"a\\"));
        check("escaped quote does not open a string",
                ShLine.complete("echo \\'"));
        check("escaped double quote does not open a string",
                ShLine.complete("echo \\\""));
        check("quote inside the other kind does not nest",
                ShLine.complete("echo \"don't\""));
    }

    private static void edges() {
        System.out.println("\n-- edges --");
        check("null is complete", ShLine.complete(null));
        check("spaces are complete", ShLine.complete("   "));
        check("lone backslash continues", !ShLine.complete("\\"));
        check("two backslashes complete", ShLine.complete("\\\\"));
        check("three backslashes continue", !ShLine.complete("\\\\\\"));
    }

    private static String show(String s) {
        return "\"" + s.replace("\\", "\\\\") + "\"";
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}

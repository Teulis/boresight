import com.boresight.Json;

/**
 * Tests for the JSON layout helper.
 *
 * The risk here is the same shape as the noise filter's: a formatter that mangles a file
 * is worse than no formatter, because the viewer is the only thing standing between the
 * user and a save file they cannot otherwise read. So the cases that matter most are the
 * ones where the input must survive untouched — strings containing braces, escapes, and
 * key order.
 */
public class JsonTest {

    private static int failures;

    public static void main(String[] args) {
        keyOrderSurvives();
        bracesInsideStringsAreNotStructure();
        escapesSurvive();
        emptyContainersStayOnOneLine();
        malformedInputIsStillShown();
        detection();
        describes();

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("JsonTest ok");
    }

    /** The whole reason this is not org.json. */
    private static void keyOrderSurvives() {
        System.out.println("\n-- key order --");
        String src = "{\"zebra\":1,\"apple\":2,\"middle\":3}";
        String out = Json.pretty(src);
        int z = out.indexOf("zebra"), a = out.indexOf("apple"), m = out.indexOf("middle");
        check("zebra still first", z >= 0 && z < a);
        check("apple still second", a < m);
    }

    private static void bracesInsideStringsAreNotStructure() {
        System.out.println("\n-- braces inside strings --");
        String src = "{\"note\":\"a { brace } and a , comma\",\"n\":1}";
        String out = Json.pretty(src);
        check("the string is intact",
                out.contains("\"a { brace } and a , comma\""));
        check("it did not split on the inner comma",
                !out.contains("and a ,\n"));
        check("the real comma did split", out.contains(",\n"));
    }

    private static void escapesSurvive() {
        System.out.println("\n-- escapes --");
        String src = "{\"path\":\"C:\\\\save\\\"1\\\"\",\"x\":2}";
        String out = Json.pretty(src);
        check("escaped backslash and quote survive",
                out.contains("\"C:\\\\save\\\"1\\\"\""));
        check("the escaped quote did not open a string",
                out.indexOf("\"x\"") > 0);
    }

    private static void emptyContainersStayOnOneLine() {
        System.out.println("\n-- empty containers --");
        check("empty object", Json.pretty("{\"a\":{}}").contains("\"a\": {}"));
        check("empty array", Json.pretty("{\"a\":[]}").contains("\"a\": []"));
        check("spaced empty object", Json.pretty("{\"a\":{   }}").contains("\"a\": {}"));
    }

    /**
     * A truncated save is exactly when you most want to look at it, so nothing may
     * refuse to render.
     */
    private static void malformedInputIsStillShown() {
        System.out.println("\n-- malformed --");
        check("truncated file still formats",
                Json.pretty("{\"a\":1,\"b\":{\"c\":2").contains("\"c\""));
        check("unterminated string does not throw",
                Json.pretty("{\"a\":\"oops").length() > 0);
        check("empty input is safe", Json.pretty("").isEmpty());
        check("null is safe", Json.pretty(null).isEmpty());
        check("not json at all passes through", Json.pretty("hello").contains("hello"));
    }

    private static void detection() {
        System.out.println("\n-- detection --");
        check("object detected", Json.looksLike("  {\"a\":1}"));
        check("array detected", Json.looksLike("[1,2,3]"));
        check("prose is not json", !Json.looksLike("hello {"));
        check("empty is not json", !Json.looksLike("   "));
        check("null is not json", !Json.looksLike(null));

        StringBuilder wide = new StringBuilder("{");
        for (int i = 0; i < 400; i++) wide.append("\"k").append(i).append("\":1,");
        check("a minified file wants formatting",
                !Json.alreadyIndented(wide.toString()));
        check("an indented file does not",
                Json.alreadyIndented("{\n  \"a\": 1,\n  \"b\": 2\n}"));
    }

    private static void describes() {
        System.out.println("\n-- describe --");
        String d = Json.describe("{\"a\":1,\"b\":{\"c\":[1,2]}}");
        check("counts the root items (" + d + ")", d.startsWith("2 items at the root"));
        check("reports the depth", d.contains("3 deep"));
        check("notices truncation",
                Json.describe("{\"a\":{\"b\":1").contains("truncated"));
        check("plain text is not described as json",
                "not JSON".equals(Json.describe("hello")));
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}

package s_exp.appia;

import clojure.lang.ExceptionInfo;
import clojure.lang.Keyword;
import clojure.lang.RT;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * One segment of a route path, in the openapi path format.
 *
 * <ul>
 *   <li>{@code posts}: a literal</li>
 *   <li>{@code {id}}: a whole-segment param</li>
 *   <li>{@code {name}.{ext}}, {@code v{major}}: a pattern of literals and
 *       params within the segment</li>
 *   <li>{@code *}: a wildcard taking the rest of the path</li>
 * </ul>
 */
final class Segment {

    enum Kind { LITERAL, PARAM, PATTERN, WILDCARD }

    private static final java.util.regex.Pattern PARAM_PATTERN = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
    private static final Segment WILDCARD = new Segment(Kind.WILDCARD, null, null, null);

    final Kind kind;
    final String literal;
    final Keyword param;
    final Object[] parts;

    private Segment(Kind kind, String literal, Keyword param, Object[] parts) {
        this.kind = kind;
        this.literal = literal;
        this.param = param;
        this.parts = parts;
    }

    /** The segments of {@code path}, ignoring empty ones. */
    static List<Segment> parse(String path) {
        List<Segment> segments = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                segments.add(parseSegment(s));
            }
        }
        return segments;
    }

    private static Segment parseSegment(String s) {
        if (s.equals("*")) {
            return WILDCARD;
        }
        Matcher m = PARAM_PATTERN.matcher(s);
        if (m.matches()) {
            return new Segment(Kind.PARAM, null, Keyword.intern(m.group(1)), null);
        }
        m.reset();
        // alternating literals and param keys, e.g. "{name}.{ext}" -> [:name "." :ext]
        List<Object> parts = new ArrayList<>();
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) {
                parts.add(s.substring(pos, m.start()));
            } else if (!parts.isEmpty()) {
                // without a literal between them there is no way to split the values
                throw new ExceptionInfo("Ambiguous route segment, adjacent params: " + s,
                        RT.map(Keyword.intern("segment"), s));
            }
            parts.add(Keyword.intern(m.group(1)));
            pos = m.end();
        }
        if (parts.isEmpty()) {
            return new Segment(Kind.LITERAL, s, null, null);
        }
        if (pos < s.length()) {
            parts.add(s.substring(pos));
        }
        return new Segment(Kind.PATTERN, null, null, parts.toArray());
    }
}

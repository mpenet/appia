package s_exp.appia;

/**
 * Sub-segment pattern child of a trie node, such as {@code {name}.{ext}} or
 * {@code urn:{type}:{id}}.
 *
 * <p>Parts are literal {@code String}s and param {@code Keyword}s in segment
 * order. Two params are never adjacent, so each param ends at the next
 * occurrence of the literal after it, or at the end of the segment.
 */
final class Pattern {

    private final Object[] parts;
    final Node child;

    Pattern(Object[] parts, Node child) {
        this.parts = parts;
        this.child = child;
    }

    /** Whether the segment {@code uri[start, end)} matches this pattern. */
    boolean matches(String uri, int start, int end) {
        return walk(uri, start, end, null, 0);
    }

    /**
     * Writes the params of the matching segment {@code uri[start, end)} into
     * {@code found}, from capture {@code slot} onwards.
     */
    void capture(String uri, int start, int end, Found found, int slot) {
        walk(uri, start, end, found, slot);
    }

    // Matches uri[start, end) against the parts, capturing into found unless
    // it is null.
    private boolean walk(String uri, int start, int end, Found found, int slot) {
        int pos = start;
        int n = parts.length;
        for (int i = 0; i < n; i++) {
            Object part = parts[i];
            if (part instanceof String) {
                String literal = (String) part;
                int len = literal.length();
                if (pos + len > end || !uri.regionMatches(pos, literal, 0, len)) {
                    return false;
                }
                pos += len;
            } else {
                int valueStart = pos;
                int valueEnd;
                if (i == n - 1) {
                    // trailing param takes the rest of the segment
                    valueEnd = end;
                    pos = end;
                } else {
                    String delimiter = (String) parts[++i];
                    valueEnd = indexOf(uri, delimiter, pos, end);
                    if (valueEnd < 0) {
                        return false;
                    }
                    pos = valueEnd + delimiter.length();
                }
                if (found != null) {
                    found.capture(slot++, part, uri.substring(valueStart, valueEnd));
                }
            }
        }
        return pos == end;
    }

    // First index of s within uri[from, end), or -1. Segments never contain
    // '/', so neither do literals: an occurrence that does not fit before end
    // has no later one that does.
    private static int indexOf(String uri, String s, int from, int end) {
        int idx = s.length() == 1 ? uri.indexOf(s.charAt(0), from) : uri.indexOf(s, from);
        return idx >= 0 && idx + s.length() <= end ? idx : -1;
    }
}

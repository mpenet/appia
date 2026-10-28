package s_exp.appia;

/**
 * Sub-segment pattern child of a trie node, such as {@code {name}.{ext}} or
 * {@code urn:{type}:{id}}.
 *
 * <p>Parts are literal {@code String}s and param {@code Keyword}s in segment
 * order. Two params are never adjacent, so each param ends at the next
 * occurrence of the literal after it, or at the end of the segment. A literal
 * ending the pattern must end the segment, so the param before it ends there.
 */
final class Pattern {

    private final Object[] parts;
    final Node child;

    Pattern(Object[] parts, Node child) {
        this.parts = parts;
        this.child = child;
    }

    /**
     * Whether the segment {@code uri[start, end)} matches this pattern,
     * writing the positions of its values into {@code positions} from capture
     * {@code slot} onwards.
     */
    boolean match(String uri, int start, int end, long[] positions, int slot) {
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
                // a param value is never empty, so it runs at least to pos + 1
                int valueStart = pos;
                int valueEnd;
                if (i == n - 1) {
                    // trailing param takes the rest of the segment
                    if (pos == end) {
                        return false;
                    }
                    valueEnd = end;
                    pos = end;
                } else {
                    String delimiter = (String) parts[++i];
                    valueEnd = i == n - 1
                            ? suffixStart(uri, delimiter, pos + 1, end)
                            : indexOf(uri, delimiter, pos + 1, end);
                    if (valueEnd < 0) {
                        return false;
                    }
                    pos = valueEnd + delimiter.length();
                }
                positions[slot++] = PathParams.position(valueStart, valueEnd);
            }
        }
        return pos == end;
    }

    // Start of s when uri[from, end) ends with it, or -1. A literal ending the
    // pattern must end the segment, wherever else it occurs.
    private static int suffixStart(String uri, String s, int from, int end) {
        int idx = end - s.length();
        return idx >= from && uri.regionMatches(idx, s, 0, s.length()) ? idx : -1;
    }

    // First index of s within uri[from, end), or -1. Segments never contain
    // '/', so neither do literals: an occurrence that does not fit before end
    // has no later one that does.
    private static int indexOf(String uri, String s, int from, int end) {
        int idx = s.length() == 1 ? uri.indexOf(s.charAt(0), from) : uri.indexOf(s, from);
        return idx >= 0 && idx + s.length() <= end ? idx : -1;
    }
}

package s_exp.appia;

import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.MapEntry;
import clojure.lang.PersistentArrayMap;

/**
 * Trie node: what a URI may continue with after the segments leading here.
 *
 * <p>Each segment is tried against the children in priority order, backing
 * off to the next kind when the rest of the URI does not match below it:
 * literal children, then sub-segment patterns with the most literal text
 * first, then a whole-segment param, then a wildcard capturing the remainder.
 *
 * <p>The positions of captured values are written into a {@code long[]} of
 * capture slots on the way down, allocated at the first capture. A node's descendants only write the slots
 * after its own, so backing off keeps the slots of the path so far, and the
 * node where a match ends reads exactly the slots of its path.
 */
final class Node {

    // Value of next when the current segment is the last one of the URI.
    private static final int END = -1;

    private final Object handler;
    private final StaticChildren statics;
    private final Pattern[] patterns;
    private final Node param;
    private final Object wildcard;
    private final int captures;
    private final int captureSlots;
    private final Layout layout;
    private final Layout wildcardLayout;
    // matches ending here with no params, built once
    private final IMapEntry handlerEntry;
    private final IMapEntry wildcardEntry;

    /**
     * Route values may be anything but {@code null}, which means no route.
     *
     * @param handler value of the route ending here, or {@code null}
     * @param statics literal segment children, or {@code null}
     * @param patterns sub-segment pattern children in priority order
     * @param param child for a whole-segment param, or {@code null}
     * @param wildcard value of the wildcard route at this node, or {@code null}
     * @param captures number of params captured on the path to this node
     * @param captureSlots number of capture slots a match below this node
     * may need
     * @param layout params captured on the path to this node
     * @param wildcardLayout the same followed by the wildcard remainder, or
     * {@code null} when there is no wildcard
     */
    Node(Object handler, StaticChildren statics, Pattern[] patterns, Node param,
            Object wildcard, int captures, int captureSlots, Layout layout, Layout wildcardLayout) {
        this.handler = handler;
        this.statics = statics;
        this.patterns = patterns.length == 0 ? null : patterns;
        this.param = param;
        this.wildcard = wildcard;
        this.captures = captures;
        this.captureSlots = captureSlots;
        this.layout = layout;
        this.wildcardLayout = wildcardLayout;
        this.handlerEntry = handler != null && layout.isEmpty()
                ? MapEntry.create(handler, PersistentArrayMap.EMPTY)
                : null;
        this.wildcardEntry = wildcard != null && layout.isEmpty()
                ? MapEntry.create(wildcard, PersistentArrayMap.EMPTY)
                : null;
    }

    /** Number of capture slots a match below this node may need. */
    int captureSlots() {
        return captureSlots;
    }

    /**
     * Matches {@code uri} from {@code pos}, the start of a segment, against
     * the routes below this node. The segment runs to the next {@code /} or
     * the end of the URI, and is empty when {@code pos} is at either.
     */
    IMapEntry match(String uri, int pos, long[] positions) {
        int slash = uri.indexOf('/', pos);
        int segEnd = slash < 0 ? uri.length() : slash;
        int next = slash < 0 ? END : slash + 1;
        IMapEntry found = matchStatic(uri, pos, segEnd, next, positions);
        if (found == null && patterns != null) {
            found = matchPatterns(uri, pos, segEnd, next, positions);
        }
        if (found == null && param != null) {
            found = matchParam(uri, pos, segEnd, next, positions);
        }
        if (found == null && wildcard != null) {
            found = matchWildcard(uri, pos, positions);
        }
        return found;
    }

    /** Matches the end of the URI, all of it having led to this node. */
    IMapEntry matchEnd(String uri, long[] positions) {
        if (handler != null) {
            return handlerEntry != null ? handlerEntry : entry(handler, uri, positions, layout);
        }
        if (wildcard != null) {
            return wildcardEntry != null ? wildcardEntry : entry(wildcard, uri, positions, layout);
        }
        return null;
    }

    private IMapEntry matchStatic(String uri, int pos, int segEnd, int next, long[] positions) {
        if (statics == null) {
            return null;
        }
        Node child = statics.get(uri, pos, segEnd);
        return child == null ? null : child.continueWith(uri, next, positions);
    }

    private IMapEntry matchPatterns(String uri, int pos, int segEnd, int next, long[] positions) {
        positions = allocated(positions);
        for (Pattern pattern : patterns) {
            if (pattern.match(uri, pos, segEnd, positions, captures)) {
                IMapEntry found = pattern.child.continueWith(uri, next, positions);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // A param value is never empty.
    private IMapEntry matchParam(String uri, int pos, int segEnd, int next, long[] positions) {
        if (segEnd == pos) {
            return null;
        }
        positions = allocated(positions);
        positions[captures] = PathParams.position(pos, segEnd);
        return param.continueWith(uri, next, positions);
    }

    // The remainder starts at pos, the start of the current segment; an empty
    // one is not captured.
    private IMapEntry matchWildcard(String uri, int pos, long[] positions) {
        if (pos == uri.length()) {
            return wildcardEntry != null ? wildcardEntry : entry(wildcard, uri, positions, layout);
        }
        positions = allocated(positions);
        positions[captures] = PathParams.position(pos, uri.length());
        return entry(wildcard, uri, positions, wildcardLayout);
    }

    // The positions, allocated by the first node that captures a value, so a
    // match capturing nothing allocates none. Slots are absolute, so this
    // node's own slot count covers every capture below it.
    private long[] allocated(long[] positions) {
        return positions != null ? positions : new long[captureSlots];
    }

    // Matches the rest of the URI below this node, from next or the end.
    private IMapEntry continueWith(String uri, int next, long[] positions) {
        return next == END ? matchEnd(uri, positions) : match(uri, next, positions);
    }

    // The positions have all been written: nothing writes them once a match
    // is found, so the params can own them.
    private static IMapEntry entry(Object value, String uri, long[] positions, Layout layout) {
        IPersistentMap params = layout.isEmpty()
                ? PersistentArrayMap.EMPTY
                : new PathParams(uri, positions, layout);
        return MapEntry.create(value, params);
    }
}

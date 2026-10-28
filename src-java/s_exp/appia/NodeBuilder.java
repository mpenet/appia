package s_exp.appia;

import clojure.lang.Keyword;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable trie node used while inserting routes, frozen into a {@link Node}
 * once all routes are in.
 */
final class NodeBuilder {

    private Object handler;
    private final Map<String, NodeBuilder> statics = new LinkedHashMap<>();
    private final List<PatternChild> patterns = new ArrayList<>();
    private NodeBuilder param;
    private Keyword paramKey;
    private Object wildcard;

    private static final class PatternChild {
        final Object[] parts;
        final NodeBuilder child = new NodeBuilder();

        PatternChild(Object[] parts) {
            this.parts = parts;
        }
    }

    /**
     * Inserts the route of {@code segments} from index {@code i}. A wildcard
     * takes the rest of the path, so segments after it are ignored. A
     * whole-segment param takes the name of the last route inserted through it.
     */
    void insert(List<Segment> segments, int i, Object value) {
        if (i == segments.size()) {
            handler = value;
            return;
        }
        Segment segment = segments.get(i);
        switch (segment.kind) {
            case WILDCARD:
                wildcard = value;
                break;
            case LITERAL:
                statics.computeIfAbsent(segment.literal, k -> new NodeBuilder())
                        .insert(segments, i + 1, value);
                break;
            case PATTERN:
                patternChild(segment.parts).insert(segments, i + 1, value);
                break;
            case PARAM:
                if (param == null) {
                    param = new NodeBuilder();
                }
                paramKey = segment.param;
                param.insert(segments, i + 1, value);
                break;
            default:
                throw new IllegalStateException("unknown segment kind: " + segment.kind);
        }
    }

    // Child of the pattern with these parts, added after the existing ones
    // when new, as patterns are tried in insertion order.
    private NodeBuilder patternChild(Object[] parts) {
        for (PatternChild p : patterns) {
            if (Arrays.equals(p.parts, parts)) {
                return p.child;
            }
        }
        PatternChild p = new PatternChild(parts);
        patterns.add(p);
        return p.child;
    }

    /** Whether no route below this node has a param, pattern or wildcard. */
    boolean isStatic() {
        if (param != null || !patterns.isEmpty() || wildcard != null) {
            return false;
        }
        for (NodeBuilder child : statics.values()) {
            if (!child.isStatic()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Frozen trie of this node, {@code captures} being the number of params
     * captured on the path to it.
     */
    Node freeze(int captures) {
        String[] keys = new String[statics.size()];
        Node[] children = new Node[statics.size()];
        int i = 0;
        for (Map.Entry<String, NodeBuilder> e : statics.entrySet()) {
            keys[i] = e.getKey();
            children[i] = e.getValue().freeze(captures);
            i++;
        }
        Pattern[] frozenPatterns = new Pattern[patterns.size()];
        for (int j = 0; j < frozenPatterns.length; j++) {
            PatternChild p = patterns.get(j);
            frozenPatterns[j] = new Pattern(p.parts, p.child.freeze(captures + paramCount(p.parts)));
        }
        return new Node(handler,
                StaticChildren.of(keys, children),
                frozenPatterns,
                param == null ? null : param.freeze(captures + 1),
                paramKey,
                wildcard,
                captures);
    }

    private static int paramCount(Object[] parts) {
        int n = 0;
        for (Object part : parts) {
            if (part instanceof Keyword) {
                n++;
            }
        }
        return n;
    }
}

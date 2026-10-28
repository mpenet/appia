package s_exp.appia;

import clojure.lang.Keyword;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
    // when new, as insertion order breaks ties between patterns.
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

    /** Frozen trie of this node, {@code path} being the param keys captured on the path to it. */
    Node freeze(List<Keyword> path) {
        int slots = wildcard != null ? path.size() + 1 : path.size();
        String[] keys = new String[statics.size()];
        Node[] children = new Node[statics.size()];
        int i = 0;
        for (Map.Entry<String, NodeBuilder> e : statics.entrySet()) {
            keys[i] = e.getKey();
            children[i] = e.getValue().freeze(path);
            slots = Math.max(slots, children[i].captureSlots());
            i++;
        }
        // most specific first: more literal text to match, ties in insertion
        // order (the sort is stable)
        List<PatternChild> byPriority = new ArrayList<>(patterns);
        byPriority.sort(Comparator.comparingInt((PatternChild p) -> literalLength(p.parts)).reversed());
        Pattern[] frozenPatterns = new Pattern[byPriority.size()];
        for (int j = 0; j < frozenPatterns.length; j++) {
            PatternChild p = byPriority.get(j);
            Node child = p.child.freeze(withParams(path, p.parts));
            frozenPatterns[j] = new Pattern(p.parts, child);
            slots = Math.max(slots, child.captureSlots());
        }
        Node frozenParam = null;
        if (param != null) {
            frozenParam = param.freeze(with(path, paramKey));
            slots = Math.max(slots, frozenParam.captureSlots());
        }
        return new Node(handler,
                StaticChildren.of(keys, children),
                frozenPatterns,
                frozenParam,
                wildcard,
                path.size(),
                slots,
                Layout.of(path),
                wildcard == null ? null : Layout.of(with(path, Segment.WILDCARD_KEY)));
    }

    private static List<Keyword> with(List<Keyword> path, Keyword key) {
        List<Keyword> keys = new ArrayList<>(path);
        keys.add(key);
        return keys;
    }

    private static List<Keyword> withParams(List<Keyword> path, Object[] parts) {
        List<Keyword> keys = new ArrayList<>(path);
        for (Object part : parts) {
            if (part instanceof Keyword) {
                keys.add((Keyword) part);
            }
        }
        return keys;
    }

    private static int literalLength(Object[] parts) {
        int n = 0;
        for (Object part : parts) {
            if (part instanceof String) {
                n += ((String) part).length();
            }
        }
        return n;
    }
}

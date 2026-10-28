package s_exp.appia;

import clojure.lang.IMapEntry;
import clojure.lang.MapEntry;
import clojure.lang.PersistentArrayMap;
import java.util.HashMap;
import java.util.List;

/**
 * Routes of one request method, matched against a URI.
 *
 * <p>Fully static routes are looked up by exact URI first: a literal child
 * wins at every segment of the trie, so an exact hit is what the trie walk
 * would return. When every route is static there is no trie at all.
 */
final class PathMatcher {

    private final HashMap<String, IMapEntry> exact;
    private final Node root;

    private PathMatcher(HashMap<String, IMapEntry> exact, Node root) {
        this.exact = exact;
        this.root = root;
    }

    /** The {@code [handler path-params]} entry of the route matching {@code uri}, or {@code null}. */
    IMapEntry match(String uri) {
        IMapEntry entry = exact.get(uri);
        if (entry != null || root == null) {
            return entry;
        }
        int start = !uri.isEmpty() && uri.charAt(0) == '/' ? 1 : 0;
        Found found = root.match(uri, start);
        return found == null ? null : found.toEntry();
    }

    /** Collects the routes of one method, in insertion order. */
    static final class Builder {

        private final HashMap<String, IMapEntry> exact = new HashMap<>();
        private final NodeBuilder root = new NodeBuilder();

        /** Adds the route of {@code path}; a {@code null} value is no route. */
        void add(String path, Object value) {
            List<Segment> segments = Segment.parse(path);
            root.insert(segments, 0, value);
            if (value != null && isStatic(segments)) {
                exact.put(staticUri(segments), MapEntry.create(value, PersistentArrayMap.EMPTY));
            }
        }

        PathMatcher build() {
            return new PathMatcher(exact, root.isStatic() ? null : root.freeze(0));
        }

        private static boolean isStatic(List<Segment> segments) {
            for (Segment s : segments) {
                if (s.kind != Segment.Kind.LITERAL) {
                    return false;
                }
            }
            return true;
        }

        // The URI a static route matches: its literals joined with '/'.
        private static String staticUri(List<Segment> segments) {
            StringBuilder sb = new StringBuilder();
            for (Segment s : segments) {
                sb.append('/').append(s.literal);
            }
            return sb.length() == 0 ? "/" : sb.toString();
        }
    }
}

package s_exp.appia;

import clojure.lang.Keyword;

/**
 * Trie node: what a URI may continue with after the segments leading here.
 *
 * <p>Each segment is tried against the children in priority order, backing
 * off to the next kind when the rest of the URI does not match below it:
 * literal children, then sub-segment patterns in order, then a whole-segment
 * param, then a wildcard capturing the remainder.
 */
final class Node {

    private final Object handler;
    private final StaticChildren statics;
    private final Pattern[] patterns;
    private final Node param;
    private final Keyword paramKey;
    private final Object wildcard;
    private final int captures;

    /**
     * Route values may be anything but {@code null}, which means no route.
     *
     * @param handler  value of the route ending here, or {@code null}
     * @param statics  literal segment children, or {@code null}
     * @param patterns sub-segment pattern children in priority order
     * @param param    child for a whole-segment param, or {@code null}
     * @param paramKey name of that param
     * @param wildcard value of the wildcard route at this node, or {@code null}
     * @param captures number of params captured on the path to this node
     */
    Node(Object handler,
                StaticChildren statics,
                Pattern[] patterns,
                Node param,
                Keyword paramKey,
                Object wildcard,
                int captures) {
        this.handler = handler;
        this.statics = statics;
        this.patterns = patterns.length == 0 ? null : patterns;
        this.param = param;
        this.paramKey = paramKey;
        this.wildcard = wildcard;
        this.captures = captures;
    }

    /**
     * Matches {@code uri} from {@code pos}, the start of a segment or the end
     * of the URI, against the routes below this node.
     */
    Found match(String uri, int pos) {
        int len = uri.length();
        if (pos == len) {
            return matchEnd();
        }
        int slash = uri.indexOf('/', pos);
        int segEnd = slash < 0 ? len : slash;
        // a last segment resumes at len, so the child's end check runs
        int next = segEnd == len ? len : segEnd + 1;
        Found found = matchStatic(uri, pos, segEnd, next);
        if (found == null && patterns != null) {
            found = matchPatterns(uri, pos, segEnd, next);
        }
        if (found == null && param != null) {
            found = matchParam(uri, pos, segEnd, next);
        }
        if (found == null && wildcard != null) {
            found = matchWildcard(uri, pos);
        }
        return found;
    }

    private Found matchEnd() {
        if (handler != null) {
            return new Found(handler, captures);
        }
        if (wildcard != null) {
            return new Found(wildcard, captures);
        }
        return null;
    }

    private Found matchStatic(String uri, int pos, int segEnd, int next) {
        if (statics == null) {
            return null;
        }
        Node child = statics.get(uri, pos, segEnd);
        return child == null ? null : child.match(uri, next);
    }

    private Found matchPatterns(String uri, int pos, int segEnd, int next) {
        for (Pattern pattern : patterns) {
            if (pattern.matches(uri, pos, segEnd)) {
                Found found = pattern.child.match(uri, next);
                if (found != null) {
                    pattern.capture(uri, pos, segEnd, found, captures);
                    return found;
                }
            }
        }
        return null;
    }

    private Found matchParam(String uri, int pos, int segEnd, int next) {
        Found found = param.match(uri, next);
        if (found != null) {
            found.capture(captures, paramKey, uri.substring(pos, segEnd));
        }
        return found;
    }

    // The remainder starts at pos, the start of the current segment.
    private Found matchWildcard(String uri, int pos) {
        Found found = new Found(wildcard, captures + 1);
        found.capture(captures, Found.WILDCARD_KEY, uri.substring(pos));
        return found;
    }
}

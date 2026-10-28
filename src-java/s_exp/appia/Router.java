package s_exp.appia;

import clojure.lang.IMapEntry;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Util;
import java.util.ArrayList;
import java.util.List;

/**
 * Router matching Ring-style requests on {@code :request-method} and
 * {@code :uri}.
 *
 * <p>Immutable and safe to share between threads once created.
 */
public final class Router {

    private static final Keyword REQUEST_METHOD = Keyword.intern("request-method");
    private static final Keyword URI = Keyword.intern("uri");

    private final Object[] methods;
    private final PathMatcher[] matchers;

    private Router(Object[] methods, PathMatcher[] matchers) {
        this.methods = methods;
        this.matchers = matchers;
    }

    /**
     * Router of {@code routes}, a map of {@code [method path]} to route value.
     *
     * <p>Methods compare by value as with {@code =}. Paths use the openapi
     * path format; see {@link Segment}. Any value but {@code nil} is a route.
     *
     * @throws clojure.lang.ExceptionInfo for a segment with adjacent params,
     *         such as {@code {a}{b}}
     */
    public static Router create(Object routes) {
        List<Object> methods = new ArrayList<>();
        List<PathMatcher.Builder> builders = new ArrayList<>();
        for (ISeq s = RT.seq(routes); s != null; s = s.next()) {
            Object route = s.first();
            Object key = RT.nth(route, 0);
            Object method = RT.nth(key, 0);
            String path = (String) RT.nth(key, 1);
            int i = indexOf(methods, method);
            if (i < 0) {
                i = methods.size();
                methods.add(method);
                builders.add(new PathMatcher.Builder());
            }
            builders.get(i).add(path, RT.nth(route, 1));
        }
        PathMatcher[] matchers = new PathMatcher[builders.size()];
        for (int i = 0; i < matchers.length; i++) {
            matchers[i] = builders.get(i).build();
        }
        return new Router(methods.toArray(), matchers);
    }

    /**
     * The {@code [handler path-params]} map entry of the route matching
     * {@code request}, or {@code null}.
     */
    public IMapEntry match(Object request) {
        Object method = RT.get(request, REQUEST_METHOD);
        for (int i = 0; i < methods.length; i++) {
            if (Util.equiv(methods[i], method)) {
                Object uri = RT.get(request, URI);
                return uri == null ? null : matchers[i].match((String) uri);
            }
        }
        return null;
    }

    private static int indexOf(List<Object> methods, Object method) {
        for (int i = 0; i < methods.size(); i++) {
            if (Util.equiv(methods.get(i), method)) {
                return i;
            }
        }
        return -1;
    }
}

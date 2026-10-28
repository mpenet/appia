package s_exp.appia;

import clojure.lang.IMapEntry;
import clojure.lang.Keyword;
import clojure.lang.MapEntry;
import clojure.lang.PersistentArrayMap;

/**
 * Result of a successful trie walk: the matched handler and the captured
 * path params as key/value pairs, capture {@code n} at {@code 2n} and
 * {@code 2n + 1}.
 *
 * <p>The node where the walk ends sizes the pairs exactly; each capturing
 * node fills its own slots while the recursion unwinds, so only the matching
 * path ever allocates captured values.
 */
final class Found {

    /** Key of the remainder captured by a wildcard. */
    static final Keyword WILDCARD_KEY = Keyword.intern("*");

    private static final Object[] NO_PAIRS = new Object[0];

    private final Object handler;
    private final Object[] pairs;

    Found(Object handler, int captures) {
        this.handler = handler;
        this.pairs = captures == 0 ? NO_PAIRS : new Object[2 * captures];
    }

    void capture(int slot, Object key, String value) {
        pairs[2 * slot] = key;
        pairs[2 * slot + 1] = value;
    }

    /**
     * The match as a {@code [handler path-params]} map entry. Repeated param
     * names resolve as with {@code assoc}: later values win.
     */
    IMapEntry toEntry() {
        return MapEntry.create(handler,
                pairs.length == 0
                        ? PersistentArrayMap.EMPTY
                        : PersistentArrayMap.createAsIfByAssoc(pairs));
    }
}

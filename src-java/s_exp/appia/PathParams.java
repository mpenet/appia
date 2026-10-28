package s_exp.appia;

import clojure.lang.APersistentMap;
import clojure.lang.IEditableCollection;
import clojure.lang.IMapEntry;
import clojure.lang.IObj;
import clojure.lang.IPersistentCollection;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.ITransientCollection;
import clojure.lang.MapEntry;
import clojure.lang.PersistentArrayMap;
import java.util.Iterator;

/**
 * Path params of a match, as an immutable map of param key to value.
 *
 * <p>Holds the URI and the positions of the captured values, and takes each
 * value out of the URI when it is looked up, so a match only allocates the
 * values that are read. Reading all of it, or deriving a new map from it,
 * goes through an equal {@link PersistentArrayMap}.
 */
final class PathParams extends APersistentMap implements IObj, IEditableCollection {

    private static final long serialVersionUID = 1L;

    // never serialized: writeReplace serializes an equal PersistentArrayMap
    private final transient String uri;
    private final transient long[] positions;
    private final transient Layout layout;

    /**
     * @param positions value positions in {@code uri} by capture slot, as
     * packed by {@link #position}; owned by this map
     */
    PathParams(String uri, long[] positions, Layout layout) {
        this.uri = uri;
        this.positions = positions;
        this.layout = layout;
    }

    /** Position of the value {@code uri[start, end)}, packed in a long. */
    static long position(int start, int end) {
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    private String value(int i) {
        long p = positions[layout.slots[i]];
        return uri.substring((int) (p >>> 32), (int) p);
    }

    // Keys are keywords, which are interned: equal keys are identical.
    private int indexOf(Object key) {
        Object[] keys = layout.keys;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == key) {
                return i;
            }
        }
        return -1;
    }

    private PersistentArrayMap toMap() {
        Object[] keys = layout.keys;
        Object[] kvs = new Object[2 * keys.length];
        for (int i = 0; i < keys.length; i++) {
            kvs[2 * i] = keys[i];
            kvs[2 * i + 1] = value(i);
        }
        return new PersistentArrayMap(kvs);
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        int i = indexOf(key);
        return i < 0 ? notFound : value(i);
    }

    @Override
    public boolean containsKey(Object key) {
        return indexOf(key) >= 0;
    }

    @Override
    public IMapEntry entryAt(Object key) {
        int i = indexOf(key);
        return i < 0 ? null : MapEntry.create(layout.keys[i], value(i));
    }

    @Override
    public int count() {
        return layout.keys.length;
    }

    @Override
    public ISeq seq() {
        return toMap().seq();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Iterator iterator() {
        return toMap().iterator();
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        return toMap().assoc(key, val);
    }

    @Override
    public IPersistentMap assocEx(Object key, Object val) {
        return toMap().assocEx(key, val);
    }

    @Override
    public IPersistentMap without(Object key) {
        return toMap().without(key);
    }

    @Override
    public IPersistentCollection empty() {
        return PersistentArrayMap.EMPTY;
    }

    @Override
    public IPersistentMap meta() {
        return null;
    }

    @Override
    public IObj withMeta(IPersistentMap meta) {
        return toMap().withMeta(meta);
    }

    @Override
    public ITransientCollection asTransient() {
        return toMap().asTransient();
    }

    private Object writeReplace() {
        return toMap();
    }
}

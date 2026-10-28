package s_exp.appia;

/**
 * Literal children of a trie node: an open-addressing table keyed by a
 * constant-time hash of the segment.
 *
 * <p>Lookups hash and compare a region of the URI in place, so matching a
 * literal segment never allocates.
 */
final class StaticChildren {

    private final int[] hashes;
    private final String[] keys;
    private final Node[] children;
    private final int mask;

    private StaticChildren(int[] hashes, String[] keys, Node[] children, int mask) {
        this.hashes = hashes;
        this.keys = keys;
        this.children = children;
        this.mask = mask;
    }

    /**
     * Table mapping each of {@code keys} to the child at the same index, or
     * {@code null} when there are no keys. Keys must be distinct.
     */
    static StaticChildren of(String[] keys, Node[] children) {
        int n = keys.length;
        if (n == 0) {
            return null;
        }
        // smallest power of two holding twice the entries, so probing always
        // reaches an empty slot
        int size = Integer.highestOneBit(2 * n - 1) << 1;
        int mask = size - 1;
        int[] hashes = new int[size];
        String[] slotKeys = new String[size];
        Node[] slotChildren = new Node[size];
        for (int i = 0; i < n; i++) {
            String key = keys[i];
            int h = hash(key, 0, key.length());
            int slot = slot(h, mask);
            while (slotKeys[slot] != null) {
                slot = (slot + 1) & mask;
            }
            hashes[slot] = h;
            slotKeys[slot] = key;
            slotChildren[slot] = children[i];
        }
        return new StaticChildren(hashes, slotKeys, slotChildren, mask);
    }

    /** The child for the literal segment {@code uri[start, end)}, or {@code null}. */
    Node get(String uri, int start, int end) {
        int h = hash(uri, start, end);
        int len = end - start;
        for (int slot = slot(h, mask); ; slot = (slot + 1) & mask) {
            String key = keys[slot];
            if (key == null) {
                return null;
            }
            if (hashes[slot] == h
                    && key.length() == len
                    && uri.regionMatches(start, key, 0, len)) {
                return children[slot];
            }
        }
    }

    // Hash of s[start, end) from its length and its first, middle and last
    // chars. Probes compare the full region, so the hash only needs to spread
    // typical sibling segments.
    private static int hash(String s, int start, int end) {
        int len = end - start;
        if (len == 0) {
            return 0;
        }
        int h = len;
        h = 31 * h + s.charAt(start);
        h = 31 * h + s.charAt(start + len / 2);
        h = 31 * h + s.charAt(end - 1);
        return h;
    }

    private static int slot(int h, int mask) {
        return (h ^ (h >>> 16)) & mask;
    }
}

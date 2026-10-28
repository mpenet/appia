package s_exp.appia;

import clojure.lang.Keyword;
import java.util.ArrayList;
import java.util.List;

/**
 * Path params of a trie node: the distinct param keys captured on the path to
 * it, and for each the capture slot holding its value.
 *
 * <p>A param name repeated on the path takes its last slot, so later values
 * win as with {@code assoc}.
 */
final class Layout {

    final Keyword[] keys;
    final int[] slots;

    private Layout(Keyword[] keys, int[] slots) {
        this.keys = keys;
        this.slots = slots;
    }

    /** Layout of the param keys captured on a path, slot {@code i} holding {@code path.get(i)}. */
    static Layout of(List<Keyword> path) {
        List<Keyword> keys = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < path.size(); slot++) {
            Keyword key = path.get(slot);
            int i = keys.indexOf(key);
            if (i < 0) {
                keys.add(key);
                slots.add(slot);
            } else {
                slots.set(i, slot);
            }
        }
        int[] slotArray = new int[slots.size()];
        for (int i = 0; i < slotArray.length; i++) {
            slotArray[i] = slots.get(i);
        }
        return new Layout(keys.toArray(new Keyword[0]), slotArray);
    }

    boolean isEmpty() {
        return keys.length == 0;
    }
}

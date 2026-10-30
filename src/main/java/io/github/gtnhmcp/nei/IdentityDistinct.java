package io.github.gtnhmcp.nei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Preserves registration order and distinct prototypes even when their IDs or equals results match. */
final class IdentityDistinct {

    private IdentityDistinct() {}

    static <T> List<T> of(List<? extends T> values) {
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        List<T> result = new ArrayList<>();
        for (T value : values) {
            if (seen.add(value)) result.add(value);
        }
        return result;
    }
}

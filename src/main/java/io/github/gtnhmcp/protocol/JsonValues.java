package io.github.gtnhmcp.protocol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies only JSON values; never lets a game object reach a reflective serializer. */
final class JsonValues {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_VALUES = 100000;

    private JsonValues() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> copyMap(Map<String, Object> source) {
        return (Map<String, Object>) copy(source, 0, new int[] { MAX_VALUES });
    }

    private static Object copy(Object value, int depth, int[] remaining) {
        if (depth > MAX_DEPTH || --remaining[0] < 0) {
            throw new IllegalArgumentException("JSON value exceeds complexity limit");
        }
        if (value == null || value instanceof String
            || value instanceof Boolean
            || value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof BigInteger
            || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double || value instanceof Float) {
            if (!Double.isFinite(((Number) value).doubleValue())) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                result.put((String) entry.getKey(), copy(entry.getValue(), depth + 1, remaining));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(copy(item, depth + 1, remaining));
            }
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Only detached JSON values are allowed");
    }
}

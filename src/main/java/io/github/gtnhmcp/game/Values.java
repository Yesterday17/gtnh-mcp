package io.github.gtnhmcp.game;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Values {

    private Values() {}

    public static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    public static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object raw = args.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max) {
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        }
        return (int) value;
    }
}

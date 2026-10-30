package io.github.gtnhmcp.nei;

import java.util.Map;

final class NeiArguments {

    private NeiArguments() {}

    static String string(Map<String, Object> args, String key, String fallback, int maxLength) {
        Object value = args.get(key);
        if (value == null) {
            if (fallback == null) throw new IllegalArgumentException("Missing argument: " + key);
            return fallback;
        }
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        String text = (String) value;
        if (text.length() > maxLength) throw new IllegalArgumentException(key + " is too long (max " + maxLength + ")");
        return text;
    }

    static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)
            || number != Math.rint(number)
            || number < min
            || number > max) {
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        }
        return (int) number;
    }

    static void validateNbtDepth(String snbt) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (quote != 0 && c == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                if (++depth > 32) throw new IllegalArgumentException("Item NBT nesting exceeds 32 levels");
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            }
        }
    }
}

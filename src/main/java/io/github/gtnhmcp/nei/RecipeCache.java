package io.github.gtnhmcp.nei;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** A bounded cache of detached recipe snapshots. IDs are valid only for one cache epoch. */
final class RecipeCache<T> {

    private final int capacity;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>();
    private String epoch = UUID.randomUUID()
        .toString();
    private long sequence;

    RecipeCache(int capacity, long ttlMillis, LongSupplier clock) {
        if (capacity < 1 || ttlMillis < 1) throw new IllegalArgumentException("Invalid recipe cache bounds");
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    String epoch() {
        return epoch;
    }

    String put(T value) {
        prune();
        while (entries.size() >= capacity) entries.remove(
            entries.keySet()
                .iterator()
                .next());
        String id = epoch + ":" + (++sequence);
        entries.put(id, new Entry<>(value, clock.getAsLong()));
        return id;
    }

    T get(String id) {
        prune();
        Entry<T> entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException(
                "Recipe ID expired or unknown. Run find_recipes/find_usages again to obtain a current recipeId.");
        }
        return entry.value;
    }

    void clear() {
        entries.clear();
        epoch = UUID.randomUUID()
            .toString();
        sequence = 0;
    }

    private void prune() {
        long now = clock.getAsLong();
        entries.entrySet()
            .removeIf(entry -> now - entry.getValue().createdAt >= ttlMillis);
    }

    private static final class Entry<T> {

        private final T value;
        private final long createdAt;

        private Entry(T value, long createdAt) {
            this.value = value;
            this.createdAt = createdAt;
        }
    }
}

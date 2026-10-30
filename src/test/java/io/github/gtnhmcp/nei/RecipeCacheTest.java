package io.github.gtnhmcp.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

public class RecipeCacheTest {

    @Test
    public void snapshotIdRemainsUsableUntilExpiry() {
        AtomicLong time = new AtomicLong(100);
        RecipeCache<String> cache = new RecipeCache<>(2, 20, time::get);
        String id = cache.put("recipe");
        time.set(119);
        assertEquals("recipe", cache.get(id));
        time.set(120);
        expired(cache, id);
    }

    @Test
    public void evictionAndWorldChangeInvalidateIds() {
        RecipeCache<String> cache = new RecipeCache<>(1, 100, () -> 0L);
        String first = cache.put("old");
        String second = cache.put("new");
        expired(cache, first);
        assertEquals("new", cache.get(second));
        String epoch = cache.epoch();
        cache.clear();
        assertNotEquals(epoch, cache.epoch());
        expired(cache, second);
    }

    private static void expired(RecipeCache<String> cache, String id) {
        try {
            cache.get(id);
            fail("Expected an expired recipe ID error");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                true,
                expected.getMessage()
                    .contains("expired"));
        }
    }
}

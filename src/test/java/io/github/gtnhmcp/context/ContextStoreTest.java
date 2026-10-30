package io.github.gtnhmcp.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.gtnhmcp.protocol.ResourceProvider;
import io.github.gtnhmcp.protocol.ToolExecutionException;

public class ContextStoreTest {

    @TempDir
    Path directory;

    @Test
    public void textOnlyMessageKeepsCaptureTimeSeparateFromPublicationAndSurvivesReload() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:text-only", "Text only");
        long sampledAtMillis = 1700000000000L;
        Map<String, Object> event = store
            .publish(world, null, "Only use my existing LV machines.", Map.of("sampledAtMillis", sampledAtMillis));
        assertNull(event.get("attachment"));
        assertEquals(
            Instant.ofEpochMilli(sampledAtMillis)
                .toString(),
            event.get("capturedAt"));
        assertTrue(
            Instant.parse((String) event.get("publishedAt"))
                .isAfter(Instant.ofEpochMilli(sampledAtMillis)));
        assertThrows(IllegalArgumentException.class, () -> store.publish(world, null, "  ", Map.of()));
        ContextStore restarted = new ContextStore(directory);
        assertEquals(event, restarted.message(world, (String) event.get("id")));
        assertEquals(
            2L,
            restarted.messages(world, 0, 20)
                .get("nextSeq"));
    }

    @Test
    public void restartKeepsFrozenAttachmentSequenceReceiptsAndProject() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:factory", "Factory");
        List<Object> ingredients = new ArrayList<>(List.of("iron"));
        Map<String, Object> attachment = new HashMap<>(Map.of("recipe", ingredients));
        Map<String, Object> context = new HashMap<>(Map.of("dimension", 0));
        Map<String, Object> first = store.publish(world, attachment, "build this", context);
        String id = (String) first.get("id");
        ingredients.set(0, "gold");
        attachment.clear();
        context.put("dimension", 7);
        assertEquals(Map.of("recipe", List.of("iron")), first.get("attachment"));
        assertEquals(Map.of("dimension", 0), first.get("context"));
        assertThrows(UnsupportedOperationException.class, () -> first.put("attachment", Map.of()));
        Map<String, Object> received = store.acknowledge(world, id, "adapter.one");
        assertEquals(received, store.acknowledge(world, id, "adapter.one"));
        Map<String, Object> project = new HashMap<>(Map.of("lines", List.of("iron smelting")));
        store.updateProject(world, 0, project);
        project.clear();

        ContextStore restarted = new ContextStore(directory);
        assertNull(restarted.activeWorldId());
        assertEquals(world, restarted.activateWorld("singleplayer:factory", "Factory"));
        assertEquals(received, restarted.message(world, id));
        assertEquals(
            Map.of("lines", List.of("iron smelting")),
            restarted.readProject(world)
                .get("project"));
        assertEquals(
            1L,
            restarted.readProject(world)
                .get("revision"));
        assertEquals(
            "not_verified_by_game",
            restarted.readProject(world)
                .get("verification"));
        assertEquals(
            2L,
            restarted.publish(world, Map.of(), "next", Map.of())
                .get("sequence"));
        String stored = Files.readString(directory.resolve(world + ".json"));
        assertFalse(stored.contains("singleplayer:factory"));
    }

    @Test
    public void worldsAreIsolatedAndOldCaptureCannotPublishIntoNewWorld() throws Exception {
        ContextStore store = new ContextStore(directory);
        String first = store.activateWorld("singleplayer:first", "First");
        String messageId = (String) store.publish(first, Map.of(), "one", Map.of())
            .get("id");
        store.updateProject(first, 0, Map.of("plan", "one"));
        String second = store.activateWorld("singleplayer:second", "Second");
        assertNotEquals(first, second);
        assertEquals(
            Map.of(),
            store.readProject(second)
                .get("project"));
        assertEquals(
            List.of(),
            store.messages(second, 0, 20)
                .get("messages"));
        assertEquals(
            "world_changed",
            assertThrows(ToolExecutionException.class, () -> store.publish(first, Map.of(), "stale", Map.of()))
                .getCode());
        assertEquals(
            "message_not_found",
            assertThrows(ToolExecutionException.class, () -> store.message(second, messageId)).getCode());
        assertEquals(
            "one",
            store.message(first, messageId)
                .get("userText"));
        store.deactivateWorld();
        assertEquals(
            false,
            store.readResource(ResourceProvider.MESSAGES_URI)
                .get("active"));
        assertEquals(
            "no_world",
            assertThrows(ToolExecutionException.class, () -> store.publish(first, Map.of(), "unloaded", Map.of()))
                .getCode());
        assertEquals(
            "one",
            store.message(first, messageId)
                .get("userText"));
        assertThrows(IllegalArgumentException.class, () -> store.readProject("../outside"));
        assertEquals(
            "unknown_world",
            assertThrows(ToolExecutionException.class, () -> store.readProject("w_" + "0".repeat(64))).getCode());
    }

    @Test
    public void retentionReportsGapAndPaginatesWithoutDroppingUnreadMessagesSilently() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:queue", "Queue");
        for (int i = 0; i < 131; i++) {
            store.publish(world, Map.of(), "message " + i, Map.of());
        }
        Map<String, Object> page = store.messages(world, 0, 20);
        assertEquals(true, page.get("gap"));
        assertEquals(4L, page.get("earliestSeq"));
        assertEquals(132L, page.get("nextSeq"));
        assertEquals(23L, page.get("nextCursor"));
        assertEquals(true, page.get("hasMore"));
        assertEquals(20, ((List<?>) page.get("messages")).size());
        page = store.messages(world, 130, 20);
        assertEquals(false, page.get("gap"));
        assertEquals(false, page.get("hasMore"));
        assertEquals(131L, page.get("nextCursor"));
        assertEquals(
            List.of(),
            store.messages(world, 131, 20)
                .get("messages"));
        assertThrows(IllegalArgumentException.class, () -> store.messages(world, 0, 21));
        assertEquals(
            "invalid_cursor",
            assertThrows(ToolExecutionException.class, () -> store.messages(world, 132, 1)).getCode());
        assertFalse(
            store.readResource(ResourceProvider.MESSAGES_URI)
                .containsKey("messages"));
    }

    @Test
    public void byteBudgetBoundsQueueAndPagesAndLargeMessagesFailBeforePublishing() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:large", "Large");
        for (int i = 0; i < 25; i++) {
            store.publish(world, Map.of("snapshot", "x".repeat(100000)), "message", Map.of());
        }
        Map<String, Object> page = store.messages(world, 0, 20);
        assertEquals(true, page.get("gap"));
        assertTrue(((Number) page.get("retainedMessages")).intValue() < 25);
        assertEquals(2, ((List<?>) page.get("messages")).size());
        assertEquals(true, page.get("hasMore"));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.publish(world, Map.of("huge", "x".repeat(128 * 1024)), "", Map.of()));
        assertEquals(
            26L,
            store.readResource(ResourceProvider.MESSAGES_URI)
                .get("nextSeq"));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.publish(world, Map.of("gameObject", new Object()), "", Map.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> store.updateProject(world, 0, Map.of("huge", "x".repeat(128 * 1024))));
        assertEquals(
            0L,
            store.readProject(world)
                .get("revision"));
    }

    @Test
    public void revisionConflictPreservesProjectAndWorldCacheEvictionReloads() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:original", "Original");
        store.updateProject(world, 0, Map.of("plan", "original"));
        assertEquals(
            "revision_conflict",
            assertThrows(
                ToolExecutionException.class,
                () -> store.updateProject(world, 0, Map.of("plan", "stale overwrite"))).getCode());
        for (int i = 0; i < 6; i++) {
            store.activateWorld("singleplayer:other-" + i, "Other " + i);
        }
        assertEquals(
            Map.of("plan", "original"),
            store.readProject(world)
                .get("project"));
        assertEquals(
            1L,
            store.readProject(world)
                .get("revision"));
    }

    @Test
    public void failedWriteNeverPublishesUpdatesOrNotifiesListeners() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:write-failure", "Write failure");
        String id = (String) store.publish(world, Map.of(), "original", Map.of())
            .get("id");
        AtomicInteger notifications = new AtomicInteger();
        store.onResourceChanged(uri -> notifications.incrementAndGet());
        Path file = directory.resolve(world + ".json");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("block-replacement"), "keep");
        assertThrows(IOException.class, () -> store.publish(world, Map.of(), "not saved", Map.of()));
        assertThrows(IOException.class, () -> store.updateProject(world, 0, Map.of("plan", "not saved")));
        assertThrows(IOException.class, () -> store.acknowledge(world, id, "adapter"));
        assertEquals(
            2L,
            store.readResource(ResourceProvider.MESSAGES_URI)
                .get("nextSeq"));
        assertEquals(
            0L,
            store.readProject(world)
                .get("revision"));
        assertEquals(
            Map.of(),
            store.message(world, id)
                .get("acknowledgements"));
        assertEquals(0, notifications.get());
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    public void corruptFileIsPreservedAndNeverSilentlyReset() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:corrupt", "Corrupt");
        Path file = directory.resolve(world + ".json");
        Files.writeString(file, "not-json");
        ContextStore restarted = new ContextStore(directory);
        assertThrows(IOException.class, () -> restarted.activateWorld("singleplayer:corrupt", "Corrupt"));
        assertNull(restarted.activeWorldId());
        assertEquals("not-json", Files.readString(file));
    }

    @Test
    public void listenersRunOutsideLockAndCanUnsubscribe() throws Exception {
        ContextStore store = new ContextStore(directory);
        String world = store.activateWorld("singleplayer:listeners", "Listeners");
        List<String> notifications = new ArrayList<>();
        AutoCloseable subscription = store.onResourceChanged(uri -> {
            try {
                assertEquals(
                    world,
                    CompletableFuture.supplyAsync(store::activeWorldId)
                        .get(2, TimeUnit.SECONDS));
                notifications.add(uri);
            } catch (Exception e) {
                throw new AssertionError("Notification held the context store lock", e);
            }
        });
        store.publish(world, Map.of(), "hello", Map.of());
        store.updateProject(world, 0, Map.of());
        assertEquals(List.of(ResourceProvider.MESSAGES_URI, ResourceProvider.PROJECT_URI), notifications);
        subscription.close();
        store.publish(world, Map.of(), "unsubscribed", Map.of());
        assertEquals(2, notifications.size());
    }
}

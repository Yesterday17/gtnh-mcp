package io.github.gtnhmcp.context;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.gtnhmcp.protocol.ResourceProvider;
import io.github.gtnhmcp.protocol.ToolExecutionException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

/**
 * Local, bounded context storage. All persisted values are detached JSON snapshots. Successful mutations have already
 * reached an atomically replaced file; callbacks run after releasing the store lock. Client project notes are never
 * presented as observations verified by the game.
 */
public final class ContextStore implements ResourceProvider {

    public static final int MAX_MESSAGES = 128;
    public static final int MAX_MESSAGE_BYTES = 128 * 1024;
    public static final int MAX_PROJECT_BYTES = 128 * 1024;
    private static final int MAX_QUEUE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PAGE_BYTES = 256 * 1024;
    private static final int MAX_FILE_BYTES = MAX_QUEUE_BYTES + MAX_PROJECT_BYTES + 65536;
    private static final int MAX_CACHED_WORLDS = 4;
    private static final Logger LOGGER = Logger.getLogger(ContextStore.class.getName());
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<Map<String, Object>>() {};

    private final Object lock = new Object();
    private final Path directory;
    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
    private final LinkedHashMap<String, WorldState> worlds = new LinkedHashMap<>(8, 0.75f, true);
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private String activeWorldId;

    public ContextStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
    }

    /** storageKey is a stable local identity, such as singleplayer:folderName; only its digest is written. */
    public String activateWorld(String storageKey, String displayName) throws IOException {
        requireText(storageKey, "storageKey", 4096);
        requireText(displayName, "displayName", 256);
        String worldId = worldId(storageKey);
        synchronized (lock) {
            WorldState state = worlds.get(worldId);
            if (state == null) {
                if (Files.exists(path(worldId), LinkOption.NOFOLLOW_LINKS)) {
                    state = load(worldId);
                } else {
                    state = new WorldState(worldId, displayName, 1, List.of(), 0, null, Map.of());
                    persist(state);
                }
            }
            activeWorldId = worldId;
            cache(state);
        }
        changed(ResourceProvider.MESSAGES_URI);
        changed(ResourceProvider.PROJECT_URI);
        return worldId;
    }

    public void deactivateWorld() {
        synchronized (lock) {
            activeWorldId = null;
        }
        changed(ResourceProvider.MESSAGES_URI);
        changed(ResourceProvider.PROJECT_URI);
    }

    public String activeWorldId() {
        synchronized (lock) {
            return activeWorldId;
        }
    }

    /** Publishes only to the world captured by the caller, never to a subsequently opened save. */
    public Map<String, Object> publish(String expectedWorldId, Map<String, Object> attachment, String text,
        Map<String, Object> capturedContext) throws IOException, ToolExecutionException {
        Objects.requireNonNull(capturedContext, "capturedContext");
        if (text == null || text.length() > 16384) {
            throw new IllegalArgumentException("userText must be a string of at most 16384 characters");
        }
        if (attachment == null && text.isBlank()) {
            throw new IllegalArgumentException("A text-only context message must include a user request");
        }
        Map<String, Object> result;
        synchronized (lock) {
            if (activeWorldId == null) {
                throw failure("no_world", "Enter a world before publishing a context message.");
            }
            if (!activeWorldId.equals(expectedWorldId)) {
                throw failure("world_changed", "The captured world is no longer active; capture the selection again.");
            }
            WorldState previous = state(expectedWorldId);
            if (previous.nextSequence == Long.MAX_VALUE) {
                throw failure("sequence_exhausted", "The context message sequence is exhausted.");
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put(
                "id",
                UUID.randomUUID()
                    .toString());
            event.put("sequence", previous.nextSequence);
            event.put("worldId", expectedWorldId);
            Object sampledAt = capturedContext.get("sampledAtMillis");
            event.put(
                "capturedAt",
                sampledAt == null ? null
                    : Instant.ofEpochMilli(integer(sampledAt, "sampledAtMillis"))
                        .toString());
            event.put(
                "publishedAt",
                Instant.now()
                    .toString());
            event.put("userText", text);
            event.put("attachment", attachment);
            event.put("context", capturedContext);
            event.put("acknowledgements", Map.of());
            result = frozenMap(event);
            requireSize(result, MAX_MESSAGE_BYTES, "Context message");
            List<Map<String, Object>> messages = new ArrayList<>(previous.messages);
            messages.add(result);
            trim(messages);
            WorldState next = previous.withMessages(previous.nextSequence + 1, messages);
            persist(next);
            cache(next);
        }
        changed(ResourceProvider.MESSAGES_URI);
        return result;
    }

    /** nextCursor is the last returned sequence; nextSeq is the sequence of the next future publication. */
    public Map<String, Object> messages(String worldId, long afterSequence, int limit)
        throws IOException, ToolExecutionException {
        if (afterSequence < 0 || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("afterSequence must be nonnegative and limit must be between 1 and 20");
        }
        synchronized (lock) {
            WorldState state = state(worldId);
            if (afterSequence >= state.nextSequence) {
                throw failure("invalid_cursor", "The cursor is ahead of this world's latest message.");
            }
            List<Map<String, Object>> selected = new ArrayList<>();
            int bytes = 0;
            long cursor = afterSequence;
            for (Map<String, Object> event : state.messages) {
                long sequence = integer(event.get("sequence"), "sequence");
                if (sequence <= afterSequence) {
                    continue;
                }
                int size = mapper.writeValueAsBytes(event).length;
                if (selected.size() >= limit || (!selected.isEmpty() && bytes + size > MAX_PAGE_BYTES - 4096)) {
                    break;
                }
                selected.add(event);
                bytes += size;
                cursor = sequence;
            }
            Map<String, Object> result = messageSummary(state);
            result.put("afterSequence", afterSequence);
            result.put("nextCursor", cursor);
            result.put("gap", afterSequence < earliest(state) - 1);
            result.put("hasMore", cursor < state.nextSequence - 1);
            result.put("messages", selected);
            return frozenMap(result);
        }
    }

    public Map<String, Object> message(String worldId, String messageId) throws IOException, ToolExecutionException {
        synchronized (lock) {
            return findMessage(state(worldId), messageId);
        }
    }

    /**
     * Acknowledgement is receipt by one adapter, not model completion, exclusive consumption, or project validation.
     */
    public Map<String, Object> acknowledge(String worldId, String messageId, String consumerId)
        throws IOException, ToolExecutionException {
        if (consumerId == null || !consumerId.matches("[A-Za-z0-9._:-]{1,96}")) {
            throw new IllegalArgumentException(
                "consumerId must contain 1-96 letters, digits, dots, colons, underscores or dashes");
        }
        Map<String, Object> result;
        boolean updated = false;
        synchronized (lock) {
            WorldState previous = state(worldId);
            Map<String, Object> original = findMessage(previous, messageId);
            Map<String, Object> acknowledgements = object(original.get("acknowledgements"), "acknowledgements");
            if (acknowledgements.containsKey(consumerId)) {
                return original;
            }
            if (acknowledgements.size() >= 32) {
                throw failure("acknowledgement_limit", "This message has reached its adapter receipt limit.");
            }
            Map<String, Object> receipt = new LinkedHashMap<>(acknowledgements);
            receipt.put(
                consumerId,
                Instant.now()
                    .toString());
            Map<String, Object> replacement = new LinkedHashMap<>(original);
            replacement.put("acknowledgements", receipt);
            result = frozenMap(replacement);
            requireSize(result, MAX_MESSAGE_BYTES, "Context message including receipts");
            List<Map<String, Object>> messages = new ArrayList<>(previous.messages);
            messages.set(messages.indexOf(original), result);
            trim(messages);
            WorldState next = previous.withMessages(previous.nextSequence, messages);
            persist(next);
            cache(next);
            updated = true;
        }
        if (updated) {
            changed(ResourceProvider.MESSAGES_URI);
        }
        return result;
    }

    public Map<String, Object> readProject(String worldId) throws IOException, ToolExecutionException {
        synchronized (lock) {
            WorldState state = state(worldId);
            Map<String, Object> result = projectSummary(state);
            result.put("project", state.project);
            return frozenMap(result);
        }
    }

    public Map<String, Object> updateProject(String worldId, long expectedRevision, Map<String, Object> project)
        throws IOException, ToolExecutionException {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }
        Map<String, Object> snapshot = frozenMap(Objects.requireNonNull(project, "project"));
        requireSize(snapshot, MAX_PROJECT_BYTES, "Project context");
        Map<String, Object> result;
        synchronized (lock) {
            WorldState previous = state(worldId);
            if (previous.revision != expectedRevision) {
                throw failure(
                    "revision_conflict",
                    "Project context changed. Read the current revision before updating.");
            }
            if (previous.revision == Long.MAX_VALUE) {
                throw failure("revision_exhausted", "The project revision is exhausted.");
            }
            WorldState next = new WorldState(
                worldId,
                previous.displayName,
                previous.nextSequence,
                previous.messages,
                previous.revision + 1,
                Instant.now()
                    .toString(),
                snapshot);
            persist(next);
            cache(next);
            result = projectSummary(next);
            result.put("project", snapshot);
            result = frozenMap(result);
        }
        changed(ResourceProvider.PROJECT_URI);
        return result;
    }

    @Override
    public Map<String, Object> readResource(String uri) throws IOException, ToolExecutionException {
        if (!ResourceProvider.MESSAGES_URI.equals(uri) && !ResourceProvider.PROJECT_URI.equals(uri)) {
            throw failure("unknown_resource", "Unknown context resource URI.");
        }
        synchronized (lock) {
            if (activeWorldId == null) {
                return Map.of("active", false, "status", "no_world");
            }
            WorldState state = state(activeWorldId);
            Map<String, Object> result = ResourceProvider.MESSAGES_URI.equals(uri) ? messageSummary(state)
                : projectSummary(state);
            result.put("active", true);
            return frozenMap(result);
        }
    }

    @Override
    public AutoCloseable onResourceChanged(Consumer<String> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    private Map<String, Object> messageSummary(WorldState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", state.worldId);
        result.put("displayName", state.displayName);
        result.put("earliestSeq", earliest(state));
        result.put("latestSeq", state.nextSequence - 1);
        result.put("nextSeq", state.nextSequence);
        result.put("retainedMessages", state.messages.size());
        result.put("retentionLimit", MAX_MESSAGES);
        result.put("delivery", "bounded_replay; acknowledgement_means_received_only");
        return result;
    }

    private Map<String, Object> projectSummary(WorldState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", state.worldId);
        result.put("displayName", state.displayName);
        result.put("revision", state.revision);
        result.put("updatedAt", state.updatedAt);
        result.put("source", "client_maintained");
        result.put("verification", "not_verified_by_game");
        return result;
    }

    private static long earliest(WorldState state) {
        return state.messages.isEmpty() ? state.nextSequence
            : integer(
                state.messages.get(0)
                    .get("sequence"),
                "sequence");
    }

    private WorldState state(String worldId) throws IOException, ToolExecutionException {
        validateWorldId(worldId);
        WorldState state = worlds.get(worldId);
        if (state == null) {
            if (!Files.exists(path(worldId), LinkOption.NOFOLLOW_LINKS)) {
                throw failure("unknown_world", "No context exists for this world ID.");
            }
            state = load(worldId);
            cache(state);
        }
        return state;
    }

    private void cache(WorldState state) {
        worlds.put(state.worldId, state);
        Iterator<String> iterator = worlds.keySet()
            .iterator();
        while (worlds.size() > MAX_CACHED_WORLDS && iterator.hasNext()) {
            if (!iterator.next()
                .equals(activeWorldId)) {
                iterator.remove();
            }
        }
    }

    private static Map<String, Object> findMessage(WorldState state, String messageId) throws ToolExecutionException {
        for (Map<String, Object> event : state.messages) {
            if (event.get("id")
                .equals(messageId)) {
                return event;
            }
        }
        throw failure("message_not_found", "The message does not exist in this world or has left the bounded history.");
    }

    private void trim(List<Map<String, Object>> messages) throws IOException {
        int total = 0;
        for (Map<String, Object> event : messages) {
            total += mapper.writeValueAsBytes(event).length;
        }
        while (messages.size() > MAX_MESSAGES || total > MAX_QUEUE_BYTES) {
            total -= mapper.writeValueAsBytes(messages.remove(0)).length;
        }
    }

    private void persist(WorldState state) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("worldId", state.worldId);
        envelope.put("displayName", state.displayName);
        envelope.put("nextSequence", state.nextSequence);
        envelope.put("messages", state.messages);
        envelope.put("revision", state.revision);
        envelope.put("updatedAt", state.updatedAt);
        envelope.put("project", state.project);
        byte[] bytes = mapper.writeValueAsBytes(envelope);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Context storage exceeds its file size limit");
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".context-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                temporary,
                path(state.worldId),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private WorldState load(String worldId) throws IOException {
        Path file = path(worldId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Context file is not a regular bounded file");
        }
        try {
            Map<String, Object> envelope = mapper.readValue(Files.readAllBytes(file), MAP_TYPE);
            if (integer(envelope.get("schemaVersion"), "schemaVersion") != 1
                || !worldId.equals(envelope.get("worldId"))) {
                throw new IllegalArgumentException("Context schema or world identity mismatch");
            }
            String displayName = (String) envelope.get("displayName");
            requireText(displayName, "displayName", 256);
            long next = integer(envelope.get("nextSequence"), "nextSequence");
            long revision = integer(envelope.get("revision"), "revision");
            if (next < 1 || revision < 0 || !(envelope.get("messages") instanceof List<?>)) {
                throw new IllegalArgumentException("Invalid context counters or messages");
            }
            List<Map<String, Object>> events = new ArrayList<>();
            long previous = -1;
            int bytes = 0;
            for (Object value : (List<?>) envelope.get("messages")) {
                Map<String, Object> decoded = new LinkedHashMap<>(object(value, "message"));
                long sequence = integer(decoded.get("sequence"), "sequence");
                decoded.put("sequence", sequence);
                Map<String, Object> event = frozenMap(decoded);
                UUID.fromString((String) event.get("id"));
                if (event.get("capturedAt") != null) {
                    Instant.parse((String) event.get("capturedAt"));
                }
                Instant.parse((String) event.get("publishedAt"));
                if (!worldId.equals(event.get("worldId")) || sequence < 1
                    || sequence >= next
                    || (previous != -1 && sequence != previous + 1)
                    || !(event.get("userText") instanceof String)
                    || ((String) event.get("userText")).length() > 16384) {
                    throw new IllegalArgumentException("Invalid context message identity or sequence");
                }
                if (event.get("attachment") != null) {
                    object(event.get("attachment"), "attachment");
                } else if (((String) event.get("userText")).isBlank()) {
                    throw new IllegalArgumentException("Text-only context message has no request");
                }
                object(event.get("context"), "context");
                Map<String, Object> receipts = object(event.get("acknowledgements"), "acknowledgements");
                if (receipts.size() > 32) {
                    throw new IllegalArgumentException("Too many context message receipts");
                }
                for (Map.Entry<String, Object> receipt : receipts.entrySet()) {
                    if (!receipt.getKey()
                        .matches("[A-Za-z0-9._:-]{1,96}")) {
                        throw new IllegalArgumentException("Invalid receipt consumer");
                    }
                    Instant.parse((String) receipt.getValue());
                }
                requireSize(event, MAX_MESSAGE_BYTES, "Persisted context message");
                bytes += mapper.writeValueAsBytes(event).length;
                events.add(event);
                previous = sequence;
            }
            if (events.size() > MAX_MESSAGES || bytes > MAX_QUEUE_BYTES
                || (events.isEmpty() ? next != 1 : previous != next - 1)) {
                throw new IllegalArgumentException("Invalid context retention or sequence tail");
            }
            Map<String, Object> project = frozenMap(object(envelope.get("project"), "project"));
            requireSize(project, MAX_PROJECT_BYTES, "Persisted project context");
            String updatedAt = (String) envelope.get("updatedAt");
            if (revision != 0) {
                Instant.parse(updatedAt);
            }
            return new WorldState(worldId, displayName, next, events, revision, updatedAt, project);
        } catch (RuntimeException e) {
            throw new IOException("Context file is invalid; it has not been replaced or reset", e);
        }
    }

    private Path path(String worldId) {
        validateWorldId(worldId);
        return directory.resolve(worldId + ".json");
    }

    private static void validateWorldId(String worldId) {
        if (worldId == null || !worldId.matches("w_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A valid worldId returned by this server is required");
        }
    }

    private static String worldId(String storageKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(storageKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("w_");
            for (byte part : digest) {
                result.append(String.format("%02x", part & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void requireSize(Object value, int limit, String description) throws IOException {
        if (mapper.writeValueAsBytes(value).length > limit) {
            throw new IllegalArgumentException(description + " exceeds " + limit + " bytes");
        }
    }

    private static void requireText(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1-" + maximum + " characters");
        }
    }

    private static long integer(Object value, String name) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> frozenMap(Map<String, Object> source) {
        return object(freeze(source, 0, new int[] { 100000 }), "snapshot");
    }

    private static Object freeze(Object value, int depth, int[] remaining) {
        if (depth > 32 || --remaining[0] < 0) {
            throw new IllegalArgumentException("Context JSON exceeds its complexity limit");
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
        if (value instanceof Float || value instanceof Double) {
            if (!Double.isFinite(((Number) value).doubleValue())) {
                throw new IllegalArgumentException("Context numbers must be finite");
            }
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("Context object keys must be strings");
                }
                result.put((String) entry.getKey(), freeze(entry.getValue(), depth + 1, remaining));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object element : (List<?>) value) {
                result.add(freeze(element, depth + 1, remaining));
            }
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Context must contain detached JSON values only");
    }

    private void changed(String uri) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(uri);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Context resource notification listener failed", e);
            }
        }
    }

    private static ToolExecutionException failure(String code, String message) {
        return new ToolExecutionException(code, message);
    }

    private static final class WorldState {

        private final String worldId;
        private final String displayName;
        private final long nextSequence;
        private final List<Map<String, Object>> messages;
        private final long revision;
        private final String updatedAt;
        private final Map<String, Object> project;

        private WorldState(String worldId, String displayName, long nextSequence, List<Map<String, Object>> messages,
            long revision, String updatedAt, Map<String, Object> project) {
            this.worldId = worldId;
            this.displayName = displayName;
            this.nextSequence = nextSequence;
            this.messages = List.copyOf(messages);
            this.revision = revision;
            this.updatedAt = updatedAt;
            this.project = project;
        }

        private WorldState withMessages(long next, List<Map<String, Object>> events) {
            return new WorldState(worldId, displayName, next, events, revision, updatedAt, project);
        }
    }
}

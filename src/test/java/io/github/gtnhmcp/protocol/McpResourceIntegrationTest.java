package io.github.gtnhmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.gtnhmcp.game.GameTaskQueue;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

/** Exercises real session HTTP/SSE and SDK subscriptions, without pretending to test game UI behavior. */
class McpResourceIntegrationTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @Test
    void officialClientReadsResourcesAndReceivesOnlySubscribedChanges() throws Exception {
        FakeResources resources = new FakeResources();
        CountDownLatch updated = new CountDownLatch(1);
        try (McpEndpoint endpoint = endpoint(resources, limits(4, 2))) {
            endpoint.start();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + endpoint.getPort())
                .endpoint("/mcp")
                .requestBuilder(
                    HttpRequest.newBuilder()
                        .header("Authorization", "Bearer " + TOKEN))
                .build();
            McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(3))
                .resourcesUpdateConsumer(contents -> {
                    if (((McpSchema.TextResourceContents) contents.get(0)).text()
                        .contains("changed")) {
                        updated.countDown();
                    }
                })
                .build();
            try {
                McpSchema.InitializeResult initialized = client.initialize();
                assertEquals(McpEndpoint.PROTOCOL_VERSION, initialized.protocolVersion());
                assertTrue(
                    initialized.capabilities()
                        .resources()
                        .subscribe());
                assertEquals(
                    2,
                    client.listResources()
                        .resources()
                        .size());
                McpSchema.ReadResourceResult read = client
                    .readResource(new McpSchema.ReadResourceRequest(ResourceProvider.MESSAGES_URI));
                assertTrue(
                    ((McpSchema.TextResourceContents) read.contents()
                        .get(0)).text()
                            .contains("initial"));
                client.subscribeResource(new McpSchema.SubscribeRequest(ResourceProvider.MESSAGES_URI));
                resources.value = "changed";
                // The SDK opens its GET stream asynchronously; notifications are hints with cursor-based recovery.
                for (int i = 0; i < 40 && updated.getCount() != 0; i++) {
                    resources.publish(ResourceProvider.MESSAGES_URI);
                    updated.await(50, TimeUnit.MILLISECONDS);
                }
                assertEquals(0, updated.getCount());
                client.unsubscribeResource(new McpSchema.UnsubscribeRequest(ResourceProvider.MESSAGES_URI));
            } finally {
                client.close();
            }
        }
        assertNull(resources.listener);
    }

    @Test
    void subscriptionsArePerSessionAndDeleteClosesStream() throws Exception {
        FakeResources resources = new FakeResources();
        try (McpEndpoint endpoint = endpoint(resources, limits(4, 2))) {
            endpoint.start();
            String first = initialize(endpoint);
            String second = initialize(endpoint);
            try (EventStream one = stream(endpoint, first); EventStream two = stream(endpoint, second)) {
                assertEquals(
                    200,
                    rpc(endpoint, first, "resources/subscribe", Map.of("uri", ResourceProvider.MESSAGES_URI))
                        .statusCode());
                resources.publish(ResourceProvider.MESSAGES_URI);
                String event = one.messages.poll(2, TimeUnit.SECONDS);
                assertNotNull(event);
                assertTrue(event.contains("notifications/resources/updated"));
                assertTrue(event.contains(ResourceProvider.MESSAGES_URI));
                assertNull(two.messages.poll(150, TimeUnit.MILLISECONDS));
                rpc(endpoint, first, "resources/unsubscribe", Map.of("uri", ResourceProvider.MESSAGES_URI));
                resources.publish(ResourceProvider.MESSAGES_URI);
                assertNull(one.messages.poll(150, TimeUnit.MILLISECONDS));
                assertEquals(204, send(endpoint, first, "DELETE", null, TOKEN, null).statusCode());
                assertTrue(one.ended.await(2, TimeUnit.SECONDS));
                assertEquals(404, rpc(endpoint, first, "ping", Collections.emptyMap()).statusCode());
                assertEquals(200, rpc(endpoint, second, "ping", Collections.emptyMap()).statusCode());
            }
        }
    }

    @Test
    void getDeleteAndPostRequireAuthenticationSessionAndOrigin() throws Exception {
        FakeResources resources = new FakeResources();
        try (McpEndpoint endpoint = endpoint(resources, limits(2, 1))) {
            endpoint.start();
            String session = initialize(endpoint);
            assertEquals(401, send(endpoint, session, "GET", null, null, null).statusCode());
            assertEquals(401, send(endpoint, session, "DELETE", null, TOKEN + "wrong", null).statusCode());
            assertEquals(403, send(endpoint, session, "GET", null, TOKEN, "https://attacker.example").statusCode());
            assertEquals(400, send(endpoint, null, "GET", null, TOKEN, null).statusCode());
            assertEquals(404, send(endpoint, "missing", "GET", null, TOKEN, null).statusCode());
            assertEquals(400, rpc(endpoint, null, "resources/list", Collections.emptyMap()).statusCode());
            String unknown = rpc(endpoint, session, "resources/subscribe", Map.of("uri", "gtnh://arbitrary")).body();
            assertTrue(unknown.contains("-32002"));
            String read = rpc(endpoint, session, "resources/read", Map.of("uri", ResourceProvider.PROJECT_URI)).body();
            assertTrue(read.contains("application/json"));
            assertTrue(read.contains("initial"));
        }
    }

    @Test
    void sessionAndStreamCountsAreBoundedAndReusableAfterClose() throws Exception {
        FakeResources resources = new FakeResources();
        try (McpEndpoint endpoint = endpoint(resources, limits(2, 1))) {
            endpoint.start();
            String first = initialize(endpoint);
            String second = initialize(endpoint);
            assertEquals(429, initializeResponse(endpoint).statusCode());
            try (EventStream one = stream(endpoint, first)) {
                assertEquals(429, send(endpoint, second, "GET", null, TOKEN, null).statusCode());
                assertEquals(200, rpc(endpoint, second, "ping", Collections.emptyMap()).statusCode());
                assertEquals(204, send(endpoint, first, "DELETE", null, TOKEN, null).statusCode());
                assertTrue(one.ended.await(2, TimeUnit.SECONDS));
            }
            String replacement = initialize(endpoint);
            try (EventStream next = stream(endpoint, replacement)) {
                endpoint.stop();
                assertTrue(next.ended.await(2, TimeUnit.SECONDS));
            }
        }
        assertNull(resources.listener);
    }

    @Test
    void socketDisconnectReleasesStreamAndReconnectRetainsSubscription() throws Exception {
        FakeResources resources = new FakeResources();
        try (McpEndpoint endpoint = endpoint(resources, limits(2, 1))) {
            endpoint.start();
            String session = initialize(endpoint);
            try (EventStream first = stream(endpoint, session)) {
                rpc(endpoint, session, "resources/subscribe", Map.of("uri", ResourceProvider.MESSAGES_URI));
            }
            EventStream reconnected = null;
            for (int i = 0; i < 40 && reconnected == null; i++) {
                HttpResponse<InputStream> response = http.send(
                    request(endpoint, session, TOKEN).GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    reconnected = new EventStream(response.body());
                } else {
                    assertEquals(429, response.statusCode());
                    response.body()
                        .close();
                    Thread.sleep(50);
                }
            }
            assertNotNull(reconnected, "Disconnected stream must release its connection slot");
            try (EventStream next = reconnected) {
                resources.publish(ResourceProvider.MESSAGES_URI);
                assertNotNull(next.messages.poll(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void abandonedSessionExpiresAndReleasesCapacity() throws Exception {
        FakeResources resources = new FakeResources();
        McpEndpoint.Limits limits = new McpEndpoint.Limits(2, 4, 4096, 16384, 100, 200, 1, 1, 50, 300);
        try (McpEndpoint endpoint = endpoint(resources, limits)) {
            endpoint.start();
            String abandoned = initialize(endpoint);
            assertEquals(429, initializeResponse(endpoint).statusCode());
            HttpResponse<String> replacement = null;
            for (int i = 0; i < 40; i++) {
                Thread.sleep(50);
                replacement = initializeResponse(endpoint);
                if (replacement.statusCode() == 200) {
                    break;
                }
                assertEquals(429, replacement.statusCode());
            }
            assertNotNull(replacement);
            assertEquals(200, replacement.statusCode());
            assertEquals(404, rpc(endpoint, abandoned, "ping", Collections.emptyMap()).statusCode());
        }
    }

    @Test
    void statefulToolDeadlineCancelsPendingGameWorkAndSessionRemainsUsable() throws Exception {
        FakeResources resources = new FakeResources();
        GameTaskQueue queue = new GameTaskQueue(2);
        AtomicBoolean ran = new AtomicBoolean();
        ToolDefinition tool = new ToolDefinition("snapshot", "Read snapshot", Map.of("type", "object"));
        McpEndpoint.Limits limits = new McpEndpoint.Limits(2, 4, 4096, 16384, 100, 2000);
        try (McpEndpoint endpoint = new McpEndpoint(
            0,
            TOKEN,
            Collections.singletonList(tool),
            (name, args) -> queue.submitAndWait(() -> {
                ran.set(true);
                return Map.of("ok", true);
            }, 5000),
            resources,
            limits)) {
            endpoint.start();
            String session = initialize(endpoint);
            HttpResponse<String> result = rpc(
                endpoint,
                session,
                "tools/call",
                Map.of("name", "snapshot", "arguments", Collections.emptyMap()));
            assertEquals(200, result.statusCode());
            assertTrue(
                result.body()
                    .contains("timeout"));
            queue.drain(1, Long.MAX_VALUE);
            assertFalse(ran.get(), "A stateful request's expired game task must never run on a later tick");
            assertEquals(0, queue.size());
            assertEquals(200, rpc(endpoint, session, "ping", Collections.emptyMap()).statusCode());
        }
    }

    @Test
    void deleteDuringAcceptedToolClosesListenerAndRejectsFurtherWork() throws Exception {
        FakeResources resources = new FakeResources();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ToolDefinition tool = new ToolDefinition("snapshot", "Read snapshot", Map.of("type", "object"));
        McpEndpoint.Limits limits = new McpEndpoint.Limits(2, 4, 4096, 16384, 3000, 5000, 2, 1, 50, 6000);
        try (McpEndpoint endpoint = new McpEndpoint(0, TOKEN, Collections.singletonList(tool), (name, args) -> {
            entered.countDown();
            finish.await(3, TimeUnit.SECONDS);
            return Map.of("ok", true);
        }, resources, limits)) {
            endpoint.start();
            String session = initialize(endpoint);
            try (EventStream stream = stream(endpoint, session)) {
                CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
                    try {
                        return rpc(
                            endpoint,
                            session,
                            "tools/call",
                            Map.of("name", "snapshot", "arguments", Collections.emptyMap()));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    assertEquals(204, send(endpoint, session, "DELETE", null, TOKEN, null).statusCode());
                    assertTrue(stream.ended.await(2, TimeUnit.SECONDS));
                    assertFalse(pending.isDone(), "DELETE must not wait for the accepted tool to finish");
                    assertEquals(
                        404,
                        rpc(endpoint, session, "resources/subscribe", Map.of("uri", ResourceProvider.MESSAGES_URI))
                            .statusCode());
                    String replacement = initialize(endpoint);
                    try (EventStream next = stream(endpoint, replacement)) {
                        assertEquals(200, rpc(endpoint, replacement, "ping", Collections.emptyMap()).statusCode());
                    }
                } finally {
                    finish.countDown();
                }
                assertTrue(
                    pending.get(2, TimeUnit.SECONDS)
                        .body()
                        .contains("\"ok\":true"));
            }
        }
    }

    @Test
    void resourceFailuresDoNotExposeInternalDetailsAndWriteToolsAreAnnotated() throws Exception {
        FakeResources resources = new FakeResources();
        resources.failure = true;
        ToolDefinition write = new ToolDefinition("save_note", "Save project context", Map.of("type", "object"), false);
        try (McpEndpoint endpoint = new McpEndpoint(
            0,
            TOKEN,
            Collections.singletonList(write),
            (name, args) -> args,
            resources,
            limits(2, 1))) {
            endpoint.start();
            String session = initialize(endpoint);
            String result = rpc(endpoint, session, "resources/read", Map.of("uri", ResourceProvider.PROJECT_URI))
                .body();
            assertTrue(result.contains("-32603"));
            assertFalse(result.contains("SECRET"));
            String tools = rpc(endpoint, session, "tools/list", Collections.emptyMap()).body();
            assertTrue(tools.contains("\"readOnlyHint\":false"));
        }
    }

    private McpEndpoint endpoint(FakeResources resources, McpEndpoint.Limits limits) {
        return new McpEndpoint(0, TOKEN, Collections.emptyList(), (name, args) -> args, resources, limits);
    }

    private static McpEndpoint.Limits limits(int sessions, int streams) {
        return new McpEndpoint.Limits(2, 4, 4096, 16384, 500, 2000, sessions, streams, 50, 5000);
    }

    private HttpResponse<String> initializeResponse(McpEndpoint endpoint) throws Exception {
        return rpc(
            endpoint,
            null,
            "initialize",
            Map.of(
                "protocolVersion",
                McpEndpoint.PROTOCOL_VERSION,
                "capabilities",
                Collections.emptyMap(),
                "clientInfo",
                Map.of("name", "test", "version", "1")));
    }

    private String initialize(McpEndpoint endpoint) throws Exception {
        HttpResponse<String> response = initializeResponse(endpoint);
        assertEquals(200, response.statusCode(), response.body());
        String session = response.headers()
            .firstValue("Mcp-Session-Id")
            .orElse(null);
        assertNotNull(session);
        return session;
    }

    private HttpResponse<String> rpc(McpEndpoint endpoint, String session, String method, Map<String, Object> params)
        throws Exception {
        String body = McpJsonDefaults.getMapper()
            .writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        return send(endpoint, session, "POST", body, TOKEN, null);
    }

    private HttpRequest.Builder request(McpEndpoint endpoint, String session, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.getEndpointUrl()))
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpEndpoint.PROTOCOL_VERSION);
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request;
    }

    private HttpResponse<String> send(McpEndpoint endpoint, String session, String method, String body, String token,
        String origin) throws Exception {
        HttpRequest.Builder request = request(endpoint, session, token);
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(
            method,
            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private EventStream stream(McpEndpoint endpoint, String session) throws Exception {
        HttpResponse<InputStream> response = http.send(
            request(endpoint, session, TOKEN).GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return new EventStream(response.body());
    }

    private static final class EventStream implements AutoCloseable {

        private final InputStream input;
        private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(64);
        private final CountDownLatch ended = new CountDownLatch(1);

        private EventStream(InputStream input) throws Exception {
            this.input = input;
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertTrue(
                reader.readLine()
                    .startsWith(": connected"));
            Thread thread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            messages.offer(line.substring(6));
                        }
                    }
                } catch (Exception disconnected) {
                    // Closing the peer or endpoint terminates the reader.
                } finally {
                    ended.countDown();
                }
            }, "test-mcp-sse-reader");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() throws Exception {
            input.close();
        }
    }

    private static final class FakeResources implements ResourceProvider {

        private volatile Consumer<String> listener;
        private volatile String value = "initial";
        private boolean failure;

        @Override
        public Map<String, Object> readResource(String uri) {
            if (failure) {
                throw new IllegalStateException("SECRET internal path");
            }
            return Map.of("uri", uri, "value", value);
        }

        @Override
        public AutoCloseable onResourceChanged(Consumer<String> listener) {
            this.listener = listener;
            return () -> this.listener = null;
        }

        private void publish(String uri) {
            listener.accept(uri);
        }
    }
}

package io.github.gtnhmcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.gtnhmcp.LocalConfig;
import io.github.gtnhmcp.context.ContextStore;
import io.github.gtnhmcp.protocol.McpEndpoint;
import io.github.gtnhmcp.protocol.ResourceProvider;
import io.modelcontextprotocol.json.McpJsonDefaults;

/** Real MCP listener changes, without claiming to exercise a running Minecraft GUI. */
class ServerLifecycleHttpTest {

    @TempDir
    Path directory;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build();
    private int nextId;

    @Test
    void occupiedPortPreservesSessionAndRebindRetainsWorldInboxAcrossDisable() throws Exception {
        LocalConfig initial = LocalConfig.load(directory)
            .withServerSettings(false, freePort());
        initial.save();
        ContextStore store = new ContextStore(directory.resolve("context"));
        String world = store.activateWorld("http-lifecycle-test", "Lifecycle test");
        Map<String, Object> message = store.publish(world, null, "How can I produce platinum?", Map.of());
        store.updateProject(world, 0, Map.of("goal", "Produce platinum"));
        ServerLifecycle lifecycle = new ServerLifecycle(initial, config -> {
            McpEndpoint endpoint = new McpEndpoint(
                config.port,
                config.token,
                List.of(),
                (name, args) -> Map.of(),
                store);
            return new ServerLifecycle.Endpoint() {

                @Override
                public void start() throws Exception {
                    endpoint.start();
                }

                @Override
                public void close() {
                    endpoint.stop();
                }
            };
        }, LocalConfig::save);
        try {
            assertTrue(
                lifecycle.start()
                    .get(10, TimeUnit.SECONDS).success);
            assertFalse(lifecycle.state().running);
            assertTrue(
                lifecycle.apply(true, initial.port)
                    .get(10, TimeUnit.SECONDS).success);
            String firstSession = initialize(initial.port, initial.token);
            assertResource(initial.port, initial.token, firstSession, world, 1);

            try (ServerSocket occupied = socket()) {
                ServerLifecycle.Result failure = lifecycle.apply(true, occupied.getLocalPort())
                    .get(10, TimeUnit.SECONDS);
                assertFalse(failure.success);
                assertEquals("bind_failed", failure.errorCode);
                assertEquals(initial.port, lifecycle.state().port);
                assertEquals(initial.port, LocalConfig.load(directory).port);
                assertEquals(200, rpc(initial.port, initial.token, firstSession, "ping", Map.of()).statusCode());
            }

            int replacementPort = freePort();
            assertTrue(
                lifecycle.apply(true, replacementPort)
                    .get(10, TimeUnit.SECONDS).success);
            assertEquals(replacementPort, LocalConfig.load(directory).port);
            assertEquals(initial.token, LocalConfig.load(directory).token);
            assertThrows(IOException.class, () -> rpc(initial.port, initial.token, firstSession, "ping", Map.of()));
            assertEquals(404, rpc(replacementPort, initial.token, firstSession, "ping", Map.of()).statusCode());
            String secondSession = initialize(replacementPort, initial.token);
            assertResource(replacementPort, initial.token, secondSession, world, 1);
            assertEquals(message, store.message(world, (String) message.get("id")));
            assertEquals(
                Map.of("goal", "Produce platinum"),
                store.readProject(world)
                    .get("project"));

            assertTrue(
                lifecycle.apply(false, replacementPort)
                    .get(10, TimeUnit.SECONDS).success);
            assertFalse(lifecycle.state().running);
            assertFalse(LocalConfig.load(directory).enabled);
            assertThrows(IOException.class, () -> rpc(replacementPort, initial.token, secondSession, "ping", Map.of()));
            store.publish(world, null, "Prefer the existing ore processing line.", Map.of());
            assertTrue(
                lifecycle.apply(true, replacementPort)
                    .get(10, TimeUnit.SECONDS).success);
            String thirdSession = initialize(replacementPort, initial.token);
            assertResource(replacementPort, initial.token, thirdSession, world, 2);
            assertEquals(world, store.activeWorldId());
            assertEquals(
                1L,
                store.readProject(world)
                    .get("revision"));
        } finally {
            lifecycle.closeAsync()
                .get(10, TimeUnit.SECONDS);
        }
    }

    private void assertResource(int port, String token, String session, String world, int retained) throws Exception {
        HttpResponse<String> response = rpc(
            port,
            token,
            session,
            "resources/read",
            Map.of("uri", ResourceProvider.MESSAGES_URI));
        assertEquals(200, response.statusCode());
        assertTrue(
            response.body()
                .contains(world));
        assertTrue(
            response.body()
                .contains("\\\"retainedMessages\\\":" + retained));
    }

    private String initialize(int port, String token) throws Exception {
        HttpResponse<String> response = rpc(
            port,
            token,
            null,
            "initialize",
            Map.of(
                "protocolVersion",
                McpEndpoint.PROTOCOL_VERSION,
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "config-lifecycle-test", "version", "1")));
        assertEquals(200, response.statusCode());
        String session = response.headers()
            .firstValue("Mcp-Session-Id")
            .orElseThrow();
        HttpRequest notification = request(port, token, session)
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
            .build();
        assertEquals(
            202,
            http.send(notification, HttpResponse.BodyHandlers.ofString())
                .statusCode());
        return session;
    }

    private HttpResponse<String> rpc(int port, String token, String session, String method, Map<String, Object> params)
        throws Exception {
        String body = McpJsonDefaults.getMapper()
            .writeValueAsString(Map.of("jsonrpc", "2.0", "id", ++nextId, "method", method, "params", params));
        return http.send(
            request(port, token, session).POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(int port, String token, String session) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + token)
            .header("MCP-Protocol-Version", McpEndpoint.PROTOCOL_VERSION)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json");
        if (session != null) request.header("Mcp-Session-Id", session);
        return request;
    }

    private static ServerSocket socket() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = socket()) {
            return socket.getLocalPort();
        }
    }
}

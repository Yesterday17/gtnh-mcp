package io.github.gtnhmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.gtnhmcp.game.GameTaskQueue;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;

/** Real sockets and the official MCP client; the game callback is deliberately a fake and proves no GTNH behavior. */
class McpEndpointIntegrationTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<Map<String, Object>>() {};

    @Test
    void officialClientInitializesListsAndCallsOverRealHttp() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        try (McpEndpoint endpoint = endpoint((name, arguments) -> {
            invocations.incrementAndGet();
            assertTrue(
                Thread.currentThread()
                    .getName()
                    .startsWith("gtnh-mcp-tool-"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", "Fake test snapshot; not a running GTNH world.");
            result.put("worldSessionId", "fake-session");
            result.put("value", arguments.get("value"));
            result.put("items", Arrays.asList(map("displayName", "铜锭 \"quoted\""), map("count", 64)));
            return result;
        })) {
            endpoint.start();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + endpoint.getPort())
                .endpoint("/mcp")
                .addHttpRequestCustomizer(
                    (builder, method, uri, body, context) -> builder.header("Authorization", "Bearer " + TOKEN))
                .build();
            McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(3))
                .build();
            try {
                McpSchema.InitializeResult initialize = client.initialize();
                assertEquals(McpEndpoint.PROTOCOL_VERSION, initialize.protocolVersion());
                assertNotNull(
                    initialize.capabilities()
                        .tools());
                assertEquals(
                    1,
                    client.listTools()
                        .tools()
                        .size());
                assertEquals(
                    Boolean.TRUE,
                    client.listTools()
                        .tools()
                        .get(0)
                        .annotations()
                        .readOnlyHint());
                McpSchema.CallToolResult result = client
                    .callTool(new McpSchema.CallToolRequest("fake_snapshot", map("value", 7)));
                assertEquals(Boolean.FALSE, result.isError());
                Map<?, ?> content = (Map<?, ?>) result.structuredContent();
                assertEquals("fake-session", content.get("worldSessionId"));
                assertEquals(7, ((Number) content.get("value")).intValue());
                assertFalse(
                    result.content()
                        .isEmpty());
                // A client that forwards only TextContent must retain all nested values, not just a summary/field list.
                Map<String, Object> textOnly = parse(
                    ((McpSchema.TextContent) result.content()
                        .get(0)).text());
                assertEquals(content, textOnly);
                assertEquals("fake-session", textOnly.get("worldSessionId"));
                assertEquals(7, ((Number) textOnly.get("value")).intValue());
                assertEquals(
                    Arrays.asList(map("displayName", "铜锭 \"quoted\""), map("count", 64)),
                    textOnly.get("items"));
                assertEquals(1, invocations.get());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void authenticationOriginAndHostFailClosed() throws Exception {
        try (McpEndpoint endpoint = endpoint((name, args) -> map("ok", true))) {
            endpoint.start();
            assertEquals(401, request(endpoint, "POST", PING, null, null, "application/json").status);
            assertEquals(401, request(endpoint, "POST", PING, TOKEN + "bad", null, "application/json").status);
            // HttpURLConnection silently drops Origin; raw sockets ensure these headers reach the server.
            String host = "127.0.0.1:" + endpoint.getPort();
            assertTrue(
                rawRequest(endpoint.getPort(), host, PING, "https://attacker.example").startsWith("HTTP/1.1 403"));
            assertTrue(rawRequest(endpoint.getPort(), host, PING, "null").startsWith("HTTP/1.1 403"));
            assertTrue(rawRequest(endpoint.getPort(), host, PING, "http://" + host).startsWith("HTTP/1.1 200"));
            assertTrue(
                rawRequest(endpoint.getPort(), "evil.example:" + endpoint.getPort(), PING, null)
                    .startsWith("HTTP/1.1 403"));
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> new McpEndpoint(0, "", Collections.emptyList(), (name, args) -> args));
        assertThrows(
            IllegalArgumentException.class,
            () -> new McpEndpoint(0, null, Collections.emptyList(), (name, args) -> args));
    }

    @Test
    void rejectsMalformedProtocolAndUnsupportedHttpMethods() throws Exception {
        try (McpEndpoint endpoint = endpoint((name, args) -> args)) {
            endpoint.start();
            Response malformed = request(endpoint, "POST", "{broken", TOKEN, null, "application/json");
            assertEquals(400, malformed.status);
            assertTrue(malformed.body.contains("-32700"));
            assertEquals(400, request(endpoint, "POST", "[]", TOKEN, null, "application/json").status);
            assertEquals(
                400,
                request(
                    endpoint,
                    "POST",
                    "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}",
                    TOKEN,
                    null,
                    "application/json").status);
            assertEquals(415, request(endpoint, "POST", PING, TOKEN, null, "text/plain").status);
            assertEquals(405, request(endpoint, "GET", null, TOKEN, null, null).status);
            assertEquals(405, request(endpoint, "DELETE", null, TOKEN, null, null).status);
            Response unknown = request(
                endpoint,
                "POST",
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"missing\"}",
                TOKEN,
                null,
                "application/json");
            assertEquals(200, unknown.status);
            assertTrue(unknown.body.contains("-32601"));
        }
    }

    @Test
    void schemaValidationPreventsInvalidArgumentsReachingGameCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (McpEndpoint endpoint = endpoint((name, args) -> {
            calls.incrementAndGet();
            return args;
        })) {
            endpoint.start();
            Response invalid = request(
                endpoint,
                "POST",
                call("{\"value\":\"wrong-type\"}"),
                TOKEN,
                null,
                "application/json");
            assertEquals(200, invalid.status);
            Map<?, ?> result = (Map<?, ?>) parse(invalid.body).get("result");
            assertEquals(Boolean.TRUE, result.get("isError"));
            assertEquals(0, calls.get());
            assertEquals(200, request(endpoint, "POST", call("{\"value\":3}"), TOKEN, null, "application/json").status);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void expectedFailuresAndInternalFailuresHaveSafeToolResults() throws Exception {
        try (McpEndpoint endpoint = endpoint((name, args) -> {
            if (((Number) args.get("value")).intValue() == 1) {
                throw new ToolExecutionException("no_world", "Open a singleplayer world first.");
            }
            throw new IllegalStateException("SECRET stacktrace token/path which must not leave the game");
        })) {
            endpoint.start();
            Response expected = request(endpoint, "POST", call("{\"value\":1}"), TOKEN, null, "application/json");
            assertTrue(expected.body.contains("\"isError\":true"));
            assertTrue(expected.body.contains("no_world"));
            Response internal = request(endpoint, "POST", call("{\"value\":2}"), TOKEN, null, "application/json");
            assertTrue(internal.body.contains("internal_error"));
            assertFalse(internal.body.contains("SECRET"));
            assertFalse(internal.body.contains("IllegalStateException"));
        }
    }

    @Test
    void timesOutInterruptsWorkerAndRemainsUsable() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        McpEndpoint.Limits limits = new McpEndpoint.Limits(1, 1, 1024, 4096, 100, 2000);
        try (McpEndpoint endpoint = new McpEndpoint(0, TOKEN, Collections.singletonList(definition()), (name, args) -> {
            if (((Number) args.get("value")).intValue() == 1) {
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    throw cancelled;
                }
            }
            return map("ok", true);
        }, limits)) {
            endpoint.start();
            Response timeout = request(endpoint, "POST", call("{\"value\":1}"), TOKEN, null, "application/json");
            assertEquals(200, timeout.status);
            assertTrue(timeout.body.contains("\"isError\":true"));
            assertTrue(timeout.body.contains("timeout"));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            Response next = request(endpoint, "POST", call("{\"value\":2}"), TOKEN, null, "application/json");
            assertTrue(next.body.contains("\"isError\":false"));
        }
    }

    @Test
    void slowRequestBodyCannotExtendDeadlineAndRunExpiredGameTask() throws Exception {
        GameTaskQueue gameQueue = new GameTaskQueue(2);
        AtomicBoolean ran = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        McpEndpoint.Limits limits = new McpEndpoint.Limits(1, 1, 1024, 4096, 1800, 2000);
        try (McpEndpoint endpoint = new McpEndpoint(0, TOKEN, Collections.singletonList(definition()), (name, args) -> {
            entered.countDown();
            return gameQueue.submitAndWait(() -> {
                ran.set(true);
                return map("ok", true);
            }, 5000);
        }, limits)) {
            endpoint.start();
            try (Socket socket = new Socket("127.0.0.1", endpoint.getPort())) {
                socket.setSoTimeout(4000);
                String body = call("{\"value\":1}");
                String headers = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + endpoint.getPort()
                    + "\r\nAuthorization: Bearer "
                    + TOKEN
                    + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream"
                    + "\r\nMCP-Protocol-Version: "
                    + McpEndpoint.PROTOCOL_VERSION
                    + "\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\n\r\n";
                socket.getOutputStream()
                    .write((headers + body.substring(0, 1)).getBytes(StandardCharsets.UTF_8));
                // Consume most of the total HTTP budget before the tool starts.
                Thread.sleep(1200);
                socket.getOutputStream()
                    .write(
                        body.substring(1)
                            .getBytes(StandardCharsets.UTF_8));
                assertTrue(entered.await(500, TimeUnit.MILLISECONDS));
                // The original HTTP budget is now over, but a freshly restarted tool/queue budget would still be live.
                Thread.sleep(1100);
                gameQueue.drain(1, Long.MAX_VALUE);
                assertFalse(ran.get(), "An expired HTTP request must not execute on a later game tick");
                assertEquals(0, gameQueue.size());
            }
        }
    }

    @Test
    void rejectsOversizedBodiesAndConcurrentOverload() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        McpEndpoint.Limits limits = new McpEndpoint.Limits(1, 2, 512, 4096, 3000, 5000);
        try (McpEndpoint endpoint = new McpEndpoint(0, TOKEN, Collections.singletonList(definition()), (name, args) -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return map("ok", true);
        }, limits)) {
            endpoint.start();
            char[] oversized = new char[513];
            Arrays.fill(oversized, ' ');
            assertEquals(413, request(endpoint, "POST", new String(oversized), TOKEN, null, "application/json").status);
            CompletableFuture<Response> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return request(endpoint, "POST", call("{\"value\":1}"), TOKEN, null, "application/json");
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(429, request(endpoint, "POST", PING, TOKEN, null, "application/json").status);
            } finally {
                release.countDown();
            }
            assertEquals(200, pending.get(2, TimeUnit.SECONDS).status);
        }
    }

    @Test
    void doesNotSerializeArbitraryObjectsOrExceedCombinedWireBudget() throws Exception {
        McpEndpoint.Limits limits = new McpEndpoint.Limits(1, 1, 1024, 1024, 500, 2000);
        try (McpEndpoint endpoint = new McpEndpoint(0, TOKEN, Collections.singletonList(definition()), (name, args) -> {
            if (((Number) args.get("value")).intValue() == 1) {
                return map("world", new Object());
            }
            // This fits in structuredContent alone, but its two complete wire representations exceed 1024 bytes.
            char[] text = new char[600];
            Arrays.fill(text, 'x');
            return map("text", new String(text));
        }, limits)) {
            endpoint.start();
            assertTrue(
                request(endpoint, "POST", call("{\"value\":1}"), TOKEN, null, "application/json").body
                    .contains("internal_error"));
            Response oversized = request(endpoint, "POST", call("{\"value\":2}"), TOKEN, null, "application/json");
            assertEquals(200, oversized.status);
            assertTrue(oversized.body.contains("result_too_large"));
            assertTrue(oversized.body.contains("\"isError\":true"));
            assertTrue(oversized.body.getBytes(StandardCharsets.UTF_8).length <= limits.maxResponseBytes);
        }
    }

    private static McpEndpoint endpoint(ToolExecutor executor) {
        return new McpEndpoint(0, TOKEN, Collections.singletonList(definition()), executor);
    }

    private static ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", map("value", map("type", "integer")));
        schema.put("required", Collections.singletonList("value"));
        schema.put("additionalProperties", false);
        return new ToolDefinition("fake_snapshot", "Fake game snapshot for HTTP transport tests only.", schema);
    }

    private static Map<String, Object> map(String key, Object value) {
        return Collections.singletonMap(key, value);
    }

    private static String call(String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"fake_snapshot\",\"arguments\":"
            + arguments
            + "}}";
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return McpJsonDefaults.getMapper()
            .readValue(json, MAP_TYPE);
    }

    private static Response request(McpEndpoint endpoint, String method, String body, String token, String origin,
        String contentType) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.getEndpointUrl()).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setRequestProperty("MCP-Protocol-Version", McpEndpoint.PROTOCOL_VERSION);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            connection.setRequestProperty("Origin", origin);
        }
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, stream == null ? "" : read(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String rawRequest(int port, String host, String body, String origin) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            String request = "POST /mcp HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: close\r\nAuthorization: Bearer "
                + TOKEN
                + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nMCP-Protocol-Version: "
                + McpEndpoint.PROTOCOL_VERSION
                + (origin == null ? "" : "\r\nOrigin: " + origin)
                + "\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n"
                + body;
            socket.getOutputStream()
                .write(request.getBytes(StandardCharsets.UTF_8));
            return read(socket.getInputStream());
        }
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Response {

        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}

package io.github.gtnhmcp.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Loopback-only, authenticated Streamable HTTP adapter for the official MCP SDK. This intentionally implements the
 * 2025-11-25 baseline: stateless HTTP (no session IDs), JSON replies, and no GET/SSE subscription stream. The SDK owns
 * initialization, capability discovery, tool dispatch and JSON Schema validation. No Minecraft classes are referenced.
 */
public final class McpEndpoint implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String PATH = "/mcp";
    private static final String DEADLINE_KEY = "gtnhmcp.deadlineNanos";
    private static final Logger LOGGER = Logger.getLogger(McpEndpoint.class.getName());
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<Map<String, Object>>() {};
    private final int requestedPort;
    private final byte[] expectedAuthorization;
    private final List<ToolDefinition> definitions;
    private final ToolExecutor toolExecutor;
    private final Limits limits;
    private final McpJsonMapper jsonMapper;
    private HttpServer httpServer;
    private ThreadPoolExecutor httpWorkers;
    private ThreadPoolExecutor toolWorkers;
    private ScheduledThreadPoolExecutor deadlines;
    private Scheduler toolScheduler;
    private McpStatelessAsyncServer sdkServer;
    private Adapter adapter;
    private boolean closed;

    public McpEndpoint(int port, String token, List<ToolDefinition> tools, ToolExecutor executor) {
        this(port, token, tools, executor, Limits.defaults());
    }

    public McpEndpoint(int port, String token, List<ToolDefinition> tools, ToolExecutor executor, Limits limits) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        // Never open an unauthenticated fallback if configuration is missing or malformed.
        if (token == null || !token.matches("[A-Za-z0-9._~-]{32,512}")) {
            throw new IllegalArgumentException("A 32-512 character local bearer token is required");
        }
        this.requestedPort = port;
        this.expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.definitions = Collections
            .unmodifiableList(new java.util.ArrayList<>(Objects.requireNonNull(tools, "tools")));
        this.toolExecutor = Objects.requireNonNull(executor, "executor");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.jsonMapper = McpJsonDefaults.getMapper();
    }

    /** Starts once. Port 0 is useful for tests; production should configure an explicit port. */
    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("Endpoint has been closed");
        }
        if (httpServer != null) {
            return;
        }
        try {
            httpWorkers = pool("gtnh-mcp-http-", limits.maxConcurrentRequests + 2, limits.maxQueuedRequests);
            toolWorkers = pool("gtnh-mcp-tool-", limits.maxConcurrentRequests, limits.maxQueuedRequests);
            toolScheduler = Schedulers.fromExecutorService(toolWorkers);
            deadlines = new ScheduledThreadPoolExecutor(1, daemonFactory("gtnh-mcp-deadline-"));
            deadlines.setRemoveOnCancelPolicy(true);
            adapter = new Adapter();
            McpServer.StatelessAsyncSpecification builder = McpServer.async(adapter)
                .serverInfo("gtnh-mcp", "0.1.0")
                .capabilities(
                    McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .instructions(
                    "Read-only GTNH tools. Check world session and snapshot freshness before combining results.")
                .validateToolInputs(true)
                .strictToolNameValidation(true);
            for (ToolDefinition definition : definitions) {
                McpSchema.Tool tool = McpSchema.Tool.builder(definition.getName(), definition.getInputSchema())
                    .description(definition.getDescription())
                    .annotations(
                        McpSchema.ToolAnnotations.builder()
                            .readOnlyHint(true)
                            .destructiveHint(false)
                            .build())
                    .build();
                builder.toolCall(tool, (context, request) -> executeTool(context, request));
            }
            sdkServer = builder.build();
            httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort),
                limits.maxQueuedRequests);
            httpServer.setExecutor(httpWorkers);
            httpServer.createContext(PATH, adapter::handle);
            httpServer.start();
        } catch (IOException | RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    public synchronized int getPort() {
        return httpServer == null ? -1
            : httpServer.getAddress()
                .getPort();
    }

    public synchronized String getEndpointUrl() {
        if (httpServer == null) {
            throw new IllegalStateException("Endpoint is not running");
        }
        return "http://127.0.0.1:" + getPort() + PATH;
    }

    public synchronized void stop() {
        closed = true;
        if (adapter != null) {
            adapter.closing = true;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        try {
            if (sdkServer != null) {
                sdkServer.closeGracefully()
                    .block(Duration.ofSeconds(1));
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "MCP SDK close failed; shutting down owned workers", failure);
        } finally {
            sdkServer = null;
            if (toolWorkers != null) {
                toolWorkers.shutdownNow();
            }
            if (httpWorkers != null) {
                httpWorkers.shutdownNow();
            }
            if (deadlines != null) {
                deadlines.shutdownNow();
            }
            if (toolScheduler != null) {
                toolScheduler.dispose();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private Mono<McpSchema.CallToolResult> executeTool(McpTransportContext context, McpSchema.CallToolRequest request) {
        long requestDeadline = (Long) context.get(DEADLINE_KEY);
        long toolDeadline = Math
            .min(requestDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.toolTimeoutMillis));
        return Mono.fromCallable(() -> RequestDeadline.call(toolDeadline, () -> {
            Map<String, Object> arguments = request.arguments() == null ? Collections.emptyMap()
                : JsonValues.copyMap(request.arguments());
            Map<String, Object> result = JsonValues
                .copyMap(Objects.requireNonNull(toolExecutor.execute(request.name(), arguments), "Tool result"));
            McpSchema.CallToolResult response = McpSchema.CallToolResult.builder()
                .structuredContent(result)
                // Some clients pass only content to the model. Preserve the entire result in both MCP representations.
                .addTextContent(jsonMapper.writeValueAsString(result))
                .isError(false)
                .build();
            // Count both complete copies, including JSON escaping in TextContent, plus room for the JSON-RPC envelope.
            if (jsonMapper.writeValueAsBytes(response).length > limits.maxResponseBytes - 128) {
                return toolError("result_too_large", "Result exceeds the response limit; use a narrower query.");
            }
            return response;
        }))
            .subscribeOn(toolScheduler)
            .timeout(Duration.ofNanos(Math.max(1L, toolDeadline - System.nanoTime())))
            .onErrorResume(error -> Mono.just(safeToolError(error)));
    }

    private static McpSchema.CallToolResult safeToolError(Throwable error) {
        if (error instanceof ToolExecutionException) {
            ToolExecutionException expected = (ToolExecutionException) error;
            return toolError(expected.getCode(), expected.getMessage());
        }
        if (error instanceof TimeoutException || error instanceof InterruptedException) {
            return toolError("timeout", "The game did not provide a result before the deadline.");
        }
        if (error instanceof RejectedExecutionException) {
            return toolError("busy", "The tool queue is full; retry later.");
        }
        LOGGER.log(Level.WARNING, "MCP tool failed", error);
        return toolError("internal_error", "The tool could not complete. Check the local game log.");
    }

    private static McpSchema.CallToolResult toolError(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? code : message);
        return McpSchema.CallToolResult.builder()
            .isError(true)
            .structuredContent(Collections.singletonMap("error", error))
            .addTextContent(code + ": " + error.get("message"))
            .build();
    }

    private static ThreadPoolExecutor pool(String prefix, int threads, int queueSize) {
        return new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueSize),
            daemonFactory(prefix),
            new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** All queues and wire payloads have explicit finite limits. */
    public static final class Limits {

        public final int maxConcurrentRequests;
        public final int maxQueuedRequests;
        public final int maxRequestBytes;
        public final int maxResponseBytes;
        public final long toolTimeoutMillis;
        public final long requestTimeoutMillis;

        public Limits(int concurrent, int queued, int requestBytes, int responseBytes, long toolTimeoutMillis,
            long requestTimeoutMillis) {
            if (concurrent < 1 || concurrent > 64
                || queued < 1
                || queued > 256
                || requestBytes < 64
                || responseBytes < 256
                || toolTimeoutMillis < 1
                || requestTimeoutMillis <= toolTimeoutMillis) {
                throw new IllegalArgumentException("Invalid MCP endpoint limits");
            }
            this.maxConcurrentRequests = concurrent;
            this.maxQueuedRequests = queued;
            this.maxRequestBytes = requestBytes;
            this.maxResponseBytes = responseBytes;
            this.toolTimeoutMillis = toolTimeoutMillis;
            this.requestTimeoutMillis = requestTimeoutMillis;
        }

        public static Limits defaults() {
            return new Limits(4, 8, 256 * 1024, 2 * 1024 * 1024, 5000, 10000);
        }
    }

    private final class Adapter implements McpStatelessServerTransport {

        private final Semaphore requests = new Semaphore(limits.maxConcurrentRequests);
        private volatile McpStatelessServerHandler handler;
        private volatile boolean closing;

        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        @Override
        public List<String> protocolVersions() {
            return Collections.singletonList(PROTOCOL_VERSION);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> closing = true);
        }

        private void handle(HttpExchange exchange) throws IOException {
            boolean acquired = false;
            ScheduledFuture<?> deadline = null;
            try {
                if (!PATH.equals(
                    exchange.getRequestURI()
                        .getPath())
                    || exchange.getRequestURI()
                        .getRawQuery() != null) {
                    httpError(exchange, 404, "Not found");
                    return;
                }
                if (!validHostAndOrigin(exchange)) {
                    httpError(exchange, 403, "Invalid local host or origin");
                    return;
                }
                if (!authorized(exchange)) {
                    exchange.getResponseHeaders()
                        .set("WWW-Authenticate", "Bearer realm=\"gtnh-mcp\"");
                    httpError(exchange, 401, "Authentication required");
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders()
                        .set("Allow", "POST");
                    httpError(exchange, 405, "This endpoint accepts POST only");
                    return;
                }
                if (closing) {
                    httpError(exchange, 503, "Endpoint is stopping");
                    return;
                }
                if (!requests.tryAcquire()) {
                    exchange.getResponseHeaders()
                        .set("Retry-After", "1");
                    httpError(exchange, 429, "Too many concurrent requests");
                    return;
                }
                acquired = true;
                long requestDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.requestTimeoutMillis);
                deadline = deadlines.schedule(exchange::close, limits.requestTimeoutMillis, TimeUnit.MILLISECONDS);
                if (!isJsonContentType(singleHeader(exchange.getRequestHeaders(), "Content-Type"))) {
                    httpError(exchange, 415, "Content-Type must be application/json");
                    return;
                }
                String accept = singleHeader(exchange.getRequestHeaders(), "Accept");
                if (!accepts(accept, "application/json") || !accepts(accept, "text/event-stream")) {
                    httpError(exchange, 406, "Accept must include application/json and text/event-stream");
                    return;
                }
                String version = singleHeader(exchange.getRequestHeaders(), "MCP-Protocol-Version");
                if (version != null && !PROTOCOL_VERSION.equals(version)) {
                    httpError(exchange, 400, "Unsupported MCP protocol version");
                    return;
                }
                byte[] bytes = readBody(exchange);
                Map<String, Object> envelope;
                try {
                    envelope = jsonMapper.readValue(bytes, MAP_TYPE);
                } catch (IOException | RuntimeException malformed) {
                    rpcError(exchange, 400, null, -32700, "Parse error");
                    return;
                }
                if (!validEnvelope(envelope)) {
                    rpcError(exchange, 400, null, -32600, "Invalid JSON-RPC request");
                    return;
                }
                String method = (String) envelope.get("method");
                Object id = envelope.get("id");
                if (!"initialize".equals(method) && version == null) {
                    // The 2025-11-25 transport requires this header after initialization. We expose only that revision.
                    rpcError(exchange, 400, id, -32600, "MCP-Protocol-Version is required after initialize");
                    return;
                }
                McpTransportContext context = McpTransportContext
                    .create(Collections.singletonMap(DEADLINE_KEY, requestDeadline));
                if (!envelope.containsKey("id")) {
                    handler
                        .handleNotification(
                            context,
                            jsonMapper.convertValue(envelope, McpSchema.JSONRPCNotification.class))
                        .block(remainingTime(requestDeadline));
                    exchange.sendResponseHeaders(202, -1);
                } else {
                    McpSchema.JSONRPCRequest request = jsonMapper
                        .convertValue(envelope, McpSchema.JSONRPCRequest.class);
                    McpSchema.JSONRPCResponse response = handler.handleRequest(context, request)
                        .block(remainingTime(requestDeadline));
                    if (response == null) {
                        rpcError(exchange, 500, id, -32603, "Internal error");
                    } else if (response.error() != null && response.error()
                        .code() == -32603) {
                            // The SDK may include an exception message; never expose implementation details.
                            rpcError(exchange, 200, id, -32603, "Internal error");
                        } else {
                            sendJson(exchange, 200, response);
                        }
                }
            } catch (BodyTooLargeException tooLarge) {
                httpError(exchange, 413, "Request exceeds the configured size limit");
            } catch (Exception failure) {
                rpcError(exchange, 500, null, -32603, "Internal error");
            } finally {
                if (deadline != null) {
                    deadline.cancel(false);
                }
                if (acquired) {
                    requests.release();
                }
                exchange.close();
            }
        }

        private boolean authorized(HttpExchange exchange) {
            String authorization = singleHeader(exchange.getRequestHeaders(), "Authorization");
            return authorization != null
                && MessageDigest.isEqual(expectedAuthorization, authorization.getBytes(StandardCharsets.UTF_8));
        }

        private boolean validHostAndOrigin(HttpExchange exchange) {
            int port = exchange.getLocalAddress()
                .getPort();
            String host = singleHeader(exchange.getRequestHeaders(), "Host");
            if (!("127.0.0.1:" + port).equals(host) && !("localhost:" + port).equals(host)) {
                return false;
            }
            List<String> origins = exchange.getRequestHeaders()
                .get("Origin");
            if (origins == null) {
                return true; // Native MCP clients do not normally send Origin.
            }
            if (origins.size() != 1) {
                return false;
            }
            try {
                URI origin = URI.create(origins.get(0));
                return "http".equals(origin.getScheme()) && host.equals(origin.getRawAuthority())
                    && (origin.getRawPath() == null || origin.getRawPath()
                        .isEmpty())
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && origin.getRawUserInfo() == null;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        private byte[] readBody(HttpExchange exchange) throws IOException {
            String length = singleHeader(exchange.getRequestHeaders(), "Content-Length");
            if (length != null) {
                try {
                    if (Long.parseLong(length) > limits.maxRequestBytes) {
                        throw new BodyTooLargeException();
                    }
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid Content-Length");
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            try (InputStream input = exchange.getRequestBody()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() > limits.maxRequestBytes - read) {
                        throw new BodyTooLargeException();
                    }
                    bytes.write(buffer, 0, read);
                }
            }
            return bytes.toByteArray();
        }

        private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
            byte[] bytes = jsonMapper.writeValueAsBytes(value);
            if (bytes.length > limits.maxResponseBytes) {
                Object id = value instanceof McpSchema.JSONRPCResponse ? ((McpSchema.JSONRPCResponse) value).id()
                    : null;
                bytes = jsonMapper.writeValueAsBytes(
                    McpSchema.JSONRPCResponse
                        .error(id, new McpSchema.JSONRPCResponse.JSONRPCError(-32603, "Response too large")));
                status = 500;
            }
            exchange.getResponseHeaders()
                .set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders()
                .set("Cache-Control", "no-store");
            exchange.getResponseHeaders()
                .set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody()
                .write(bytes);
        }

        private void httpError(HttpExchange exchange, int status, String message) throws IOException {
            sendJson(exchange, status, Collections.singletonMap("error", message));
        }

        private void rpcError(HttpExchange exchange, int status, Object id, int code, String message)
            throws IOException {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", code);
            error.put("message", message);
            response.put("error", error);
            sendJson(exchange, status, response);
        }
    }

    private static Duration remainingTime(long deadlineNanos) throws TimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("HTTP request deadline elapsed");
        }
        return Duration.ofNanos(remaining);
    }

    private static boolean validEnvelope(Map<String, Object> envelope) {
        if (envelope == null || !"2.0".equals(envelope.get("jsonrpc"))
            || !(envelope.get("method") instanceof String)
            || ((String) envelope.get("method")).isEmpty()
            || envelope.containsKey("result")
            || envelope.containsKey("error")) {
            return false;
        }
        if (envelope.containsKey("id")) {
            Object id = envelope.get("id");
            if (!(id instanceof String) && !(id instanceof Integer) && !(id instanceof Long)) {
                return false;
            }
        }
        return !envelope.containsKey("params") || envelope.get("params") instanceof Map<?, ?>;
    }

    private static String singleHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private static boolean isJsonContentType(String contentType) {
        return contentType != null && "application/json".equals(
            contentType.split(";", 2)[0].trim()
                .toLowerCase(Locale.ROOT));
    }

    private static boolean accepts(String accept, String type) {
        if (accept == null) {
            return false;
        }
        for (String entry : accept.split(",")) {
            String[] parts = entry.trim()
                .split(";");
            if (type.equalsIgnoreCase(parts[0].trim())) {
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].trim()
                        .matches("q=0(?:\\.0*)?")) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;
    }
}

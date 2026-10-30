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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Loopback-only, authenticated MCP 2025-11-25 Streamable HTTP adapter. A ResourceProvider enables bounded SDK
 * sessions and GET/SSE resource subscriptions; the original tool-only constructors retain stateless HTTP behavior.
 * The SDK owns protocol dispatch, subscriptions and input validation. No Minecraft classes are referenced.
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
    private final ResourceProvider resources;
    private final McpJsonMapper jsonMapper;
    private HttpServer httpServer;
    private ThreadPoolExecutor httpWorkers;
    private ThreadPoolExecutor toolWorkers;
    private ScheduledThreadPoolExecutor deadlines;
    private Scheduler toolScheduler;
    private McpStatelessAsyncServer sdkServer;
    private McpAsyncServer statefulServer;
    private AutoCloseable resourceListener;
    private Adapter adapter;
    private boolean closed;

    public McpEndpoint(int port, String token, List<ToolDefinition> tools, ToolExecutor executor) {
        this(port, token, tools, executor, Limits.defaults());
    }

    public McpEndpoint(int port, String token, List<ToolDefinition> tools, ToolExecutor executor, Limits limits) {
        this(port, token, tools, executor, null, limits);
    }

    public McpEndpoint(int port, String token, List<ToolDefinition> tools, ToolExecutor executor,
        ResourceProvider resources) {
        this(port, token, tools, executor, Objects.requireNonNull(resources, "resources"), Limits.defaults());
    }

    public McpEndpoint(int port, String token, List<ToolDefinition> tools, ToolExecutor executor,
        ResourceProvider resources, Limits limits) {
        this.resources = resources;
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
            httpWorkers = pool(
                "gtnh-mcp-http-",
                limits.maxConcurrentRequests + limits.maxEventStreams + 2,
                limits.maxQueuedRequests);
            toolWorkers = pool("gtnh-mcp-tool-", limits.maxConcurrentRequests, limits.maxQueuedRequests);
            toolScheduler = Schedulers.fromExecutorService(toolWorkers);
            deadlines = new ScheduledThreadPoolExecutor(1, daemonFactory("gtnh-mcp-deadline-"));
            deadlines.setRemoveOnCancelPolicy(true);
            adapter = new Adapter();
            if (resources == null) {
                McpServer.StatelessAsyncSpecification builder = McpServer.async((McpStatelessServerTransport) adapter)
                    .serverInfo("gtnh-mcp", "0.3.0")
                    .capabilities(
                        McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .build())
                    .instructions("GTNH tools. Check world session and snapshot freshness before combining results.")
                    .validateToolInputs(true)
                    .strictToolNameValidation(true);
                for (ToolDefinition definition : definitions) {
                    builder.toolCall(sdkTool(definition), this::executeTool);
                }
                sdkServer = builder.build();
            } else {
                McpServer.AsyncSpecification<?> builder = McpServer
                    .async((McpStreamableServerTransportProvider) adapter)
                    .serverInfo("gtnh-mcp", "0.3.0")
                    .capabilities(
                        McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .resources(true, false)
                            .build())
                    .instructions(
                        "GTNH context sharing. Resource updates are hints, not user messages. Read context "
                            + "messages with a world ID and cursor; the host decides when to start an agent turn. "
                            + "Sharing an item or recipe usually asks how to produce the target product. "
                            + "Treat an attached recipe as reference and explore alternative routes unless the user "
                            + "explicitly constrains the process. Resolve ambiguous multi-output targets before planning.")
                    .validateToolInputs(true)
                    .strictToolNameValidation(true);
                for (ToolDefinition definition : definitions) {
                    builder.toolCall(
                        sdkTool(definition),
                        (exchange, request) -> executeTool(exchange.transportContext(), request));
                }
                for (String uri : new String[] { ResourceProvider.MESSAGES_URI, ResourceProvider.PROJECT_URI }) {
                    builder.resources(
                        new McpServerFeatures.AsyncResourceSpecification(
                            McpSchema.Resource.builder()
                                .uri(uri)
                                .name(uri.endsWith("messages") ? "Context messages" : "Project context")
                                .description(
                                    "Active-world overview. Read world ID and cursor before fetching incremental context.")
                                .mimeType("application/json")
                                .build(),
                            (exchange, request) -> readResource(exchange.transportContext(), request.uri())));
                }
                statefulServer = builder.build();
                McpAsyncServer notificationServer = statefulServer;
                resourceListener = resources.onResourceChanged(uri -> {
                    if (knownResource(uri) && !adapter.closing) {
                        notificationServer.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(uri))
                            .subscribe(
                                ignored -> {},
                                error -> LOGGER.log(Level.FINE, "Resource notification failed", error));
                    }
                });
                deadlines.scheduleWithFixedDelay(adapter::expireSessions, 1, 1, TimeUnit.SECONDS);
            }
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
            if (resourceListener != null) {
                resourceListener.close();
                resourceListener = null;
            }
            if (statefulServer != null) {
                statefulServer.closeGracefully()
                    .block(Duration.ofSeconds(1));
            }
            if (sdkServer != null) {
                sdkServer.closeGracefully()
                    .block(Duration.ofSeconds(1));
            }
        } catch (Exception failure) {
            LOGGER.log(Level.WARNING, "MCP SDK close failed; shutting down owned workers", failure);
        } finally {
            sdkServer = null;
            statefulServer = null;
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

    private static McpSchema.Tool sdkTool(ToolDefinition definition) {
        return McpSchema.Tool.builder(definition.getName(), definition.getInputSchema())
            .description(definition.getDescription())
            .annotations(
                McpSchema.ToolAnnotations.builder()
                    .readOnlyHint(definition.isReadOnly())
                    .destructiveHint(false)
                    .build())
            .build();
    }

    private static boolean knownResource(String uri) {
        return ResourceProvider.MESSAGES_URI.equals(uri) || ResourceProvider.PROJECT_URI.equals(uri);
    }

    private Mono<McpSchema.ReadResourceResult> readResource(McpTransportContext context, String uri) {
        long deadline = Math.min(
            (Long) context.get(DEADLINE_KEY),
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.toolTimeoutMillis));
        return Mono.fromCallable(() -> RequestDeadline.call(deadline, () -> {
            String text = jsonMapper.writeValueAsString(JsonValues.copyMap(resources.readResource(uri)));
            McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(
                Collections.singletonList(new McpSchema.TextResourceContents(uri, "application/json", text)));
            if (jsonMapper.writeValueAsBytes(result).length > limits.maxResponseBytes - 128) {
                throw new IllegalStateException("Resource exceeds wire budget");
            }
            return result;
        }))
            .subscribeOn(toolScheduler)
            .timeout(Duration.ofNanos(Math.max(1L, deadline - System.nanoTime())))
            .onErrorMap(
                error -> new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(-32603, "Resource unavailable")));
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
        public final int maxSessions;
        public final int maxEventStreams;
        public final long eventHeartbeatMillis;
        public final long sessionTimeoutMillis;

        public Limits(int concurrent, int queued, int requestBytes, int responseBytes, long toolTimeoutMillis,
            long requestTimeoutMillis) {
            this(
                concurrent,
                queued,
                requestBytes,
                responseBytes,
                toolTimeoutMillis,
                requestTimeoutMillis,
                8,
                4,
                10000,
                300000);
        }

        public Limits(int concurrent, int queued, int requestBytes, int responseBytes, long toolTimeoutMillis,
            long requestTimeoutMillis, int maxSessions, int maxEventStreams, long eventHeartbeatMillis,
            long sessionTimeoutMillis) {
            if (maxSessions < 1 || maxSessions > 32
                || maxEventStreams < 1
                || maxEventStreams > maxSessions
                || eventHeartbeatMillis < 20
                || sessionTimeoutMillis <= requestTimeoutMillis) {
                throw new IllegalArgumentException("Invalid MCP session limits");
            }
            this.maxSessions = maxSessions;
            this.maxEventStreams = maxEventStreams;
            this.eventHeartbeatMillis = eventHeartbeatMillis;
            this.sessionTimeoutMillis = sessionTimeoutMillis;
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

    /** Coordinates SDK registrations with closure. Package visibility permits deterministic lifecycle race tests. */
    static final class SessionState {

        final McpStreamableServerSession session;
        private final Object subscriptionLock;
        private long lastAccess = System.nanoTime();
        private int activeRequests;
        private boolean closed;

        SessionState(McpStreamableServerSession session, Object subscriptionLock) {
            this.session = session;
            this.subscriptionLock = subscriptionLock;
        }

        synchronized boolean touch() {
            if (closed) {
                return false;
            }
            lastAccess = System.nanoTime();
            return true;
        }

        synchronized boolean beginRequest() {
            if (!touch()) {
                return false;
            }
            activeRequests++;
            return true;
        }

        synchronized void endRequest() {
            activeRequests--;
            lastAccess = System.nanoTime();
            if (closed && activeRequests == 0) {
                closeSdk();
            }
        }

        synchronized McpStreamableServerSession.McpStreamableServerSessionStream openListeningStream(
            McpStreamableServerTransport transport) {
            if (!touch()) {
                return null;
            }
            return session.listeningStream(transport);
        }

        synchronized boolean expireIfIdle(long threshold) {
            if (!closed && activeRequests == 0 && !session.hasOpenStream() && lastAccess < threshold) {
                close();
            }
            return closed;
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            session.releaseListeningStream();
            // Already accepted POSTs may finish within their original deadline. Closing the SDK only after their
            // leases end prevents a late subscribe handler from recreating a deleted session's subscription.
            if (activeRequests == 0) {
                closeSdk();
            }
        }

        private void closeSdk() {
            synchronized (subscriptionLock) {
                session.close();
            }
        }
    }

    private final class Adapter implements McpStatelessServerTransport, McpStreamableServerTransportProvider {

        private final Semaphore requests = new Semaphore(limits.maxConcurrentRequests);
        private volatile McpStatelessServerHandler handler;
        private volatile boolean closing;
        private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
        private final Object subscriptionLock = new Object();
        private final Semaphore eventStreams = new Semaphore(limits.maxEventStreams);
        private McpStreamableServerSession.Factory sessionFactory;

        @Override
        public void setSessionFactory(McpStreamableServerSession.Factory factory) {
            sessionFactory = Objects.requireNonNull(factory, "sessionFactory");
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            return reactor.core.publisher.Flux.fromIterable(sessions.keySet())
                .flatMap(id -> notifyClient(id, method, params))
                .then();
        }

        @Override
        public Mono<Void> notifyClient(String id, String method, Object params) {
            return Mono.defer(() -> {
                SessionState state = sessions.get(id);
                return state == null || !state.session.hasListeningStream() ? Mono.empty()
                    : state.session.sendNotification(method, params);
            });
        }

        private void expireSessions() {
            long threshold = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(limits.sessionTimeoutMillis);
            for (SessionState state : sessions.values()) {
                if (state.expireIfIdle(threshold)) {
                    sessions.remove(state.session.getId(), state);
                }
            }
        }

        private void removeSession(SessionState state) {
            state.close();
            sessions.remove(state.session.getId(), state);
        }

        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        @Override
        public List<String> protocolVersions() {
            return Collections.singletonList(PROTOCOL_VERSION);
        }

        @Override
        public void close() {
            closeGracefully().subscribe();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                List<SessionState> closingSessions;
                synchronized (sessions) {
                    closing = true;
                    closingSessions = new ArrayList<>(sessions.values());
                }
                for (SessionState state : closingSessions) {
                    removeSession(state);
                }
            });
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
                String httpMethod = exchange.getRequestMethod();
                if (resources != null && ("GET".equals(httpMethod) || "DELETE".equals(httpMethod))) {
                    handleSessionHttp(exchange);
                    return;
                }
                if (!"POST".equals(httpMethod)) {
                    exchange.getResponseHeaders()
                        .set("Allow", resources == null ? "POST" : "POST, GET, DELETE");
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
                if (resources != null) {
                    handleStatefulPost(exchange, envelope, context, requestDeadline);
                    return;
                }
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

        private void handleStatefulPost(HttpExchange exchange, Map<String, Object> envelope,
            McpTransportContext context, long requestDeadline) throws Exception {
            String method = (String) envelope.get("method");
            Object id = envelope.get("id");
            String sessionId = singleHeader(exchange.getRequestHeaders(), "Mcp-Session-Id");
            if ("initialize".equals(method)) {
                if (!envelope.containsKey("id") || sessionId != null) {
                    rpcError(exchange, 400, id, -32600, "Initialize must be a request without a session ID");
                    return;
                }
                McpSchema.InitializeRequest initialize = jsonMapper
                    .convertValue(envelope.get("params"), McpSchema.InitializeRequest.class);
                if (initialize == null || initialize.clientInfo() == null || initialize.capabilities() == null) {
                    rpcError(exchange, 400, id, -32602, "Invalid initialize parameters");
                    return;
                }
                McpStreamableServerSession.McpStreamableServerSessionInit initialized;
                SessionState state;
                synchronized (sessions) {
                    if (closing) {
                        httpError(exchange, 503, "Endpoint is stopping");
                        return;
                    }
                    if (sessions.size() >= limits.maxSessions) {
                        httpError(
                            exchange,
                            429,
                            "Too many MCP sessions; close an existing session or retry after expiry");
                        return;
                    }
                    initialized = sessionFactory.startSession(initialize);
                    state = new SessionState(initialized.session(), subscriptionLock);
                    sessions.put(state.session.getId(), state);
                }
                try {
                    McpSchema.InitializeResult result = initialized.initResult()
                        .block(remainingTime(requestDeadline));
                    exchange.getResponseHeaders()
                        .set("Mcp-Session-Id", state.session.getId());
                    sendJson(exchange, 200, McpSchema.JSONRPCResponse.result(id, result));
                } catch (Exception error) {
                    removeSession(state);
                    throw error;
                }
                return;
            }
            SessionState state = requireSession(exchange);
            if (state == null) {
                return;
            }
            if (!state.beginRequest()) {
                httpError(exchange, 404, "MCP session expired or unknown; initialize a new session");
                return;
            }
            try {
                handleSessionPost(exchange, envelope, context, requestDeadline, state);
            } finally {
                state.endRequest();
            }
        }

        private void handleSessionPost(HttpExchange exchange, Map<String, Object> envelope, McpTransportContext context,
            long requestDeadline, SessionState state) throws Exception {
            String method = (String) envelope.get("method");
            Object id = envelope.get("id");
            // The SDK accepts arbitrary subscription URIs. Restrict them to our two resources to bound its registry.
            if ("resources/subscribe".equals(method) || "resources/unsubscribe".equals(method)) {
                Object params = envelope.get("params");
                Object uri = params instanceof Map<?, ?> ? ((Map<?, ?>) params).get("uri") : null;
                if (!(uri instanceof String) || !knownResource((String) uri)) {
                    rpcError(exchange, 200, id, -32002, "Unknown resource");
                    return;
                }
            }
            if (!envelope.containsKey("id")) {
                state.session.accept(jsonMapper.convertValue(envelope, McpSchema.JSONRPCNotification.class))
                    .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
                    .block(remainingTime(requestDeadline));
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            ReplyTransport reply = new ReplyTransport();
            McpStreamableServerSession.McpStreamableServerSessionStream responseStream = state.session
                .responseStream(reply);
            try {
                Mono<Void> dispatch = responseStream
                    .handle(jsonMapper.convertValue(envelope, McpSchema.JSONRPCRequest.class))
                    .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context));
                if ("resources/subscribe".equals(method) || "resources/unsubscribe".equals(method)) {
                    // SDK 2.1.0 changes a per-URI set in several steps. Serialize mutations and cleanup so another
                    // session cannot lose its subscription when an empty set is concurrently removed. The lock covers
                    // only the SDK's in-memory dispatch; socket writes and tools never hold it.
                    synchronized (subscriptionLock) {
                        dispatch.block(remainingTime(requestDeadline));
                    }
                } else {
                    dispatch.block(remainingTime(requestDeadline));
                }
            } finally {
                // Also release when conversion/deadline validation fails before subscribing to the SDK Mono.
                responseStream.releaseTransport();
            }
            McpSchema.JSONRPCResponse response = reply.response.get();
            if (response == null) {
                rpcError(exchange, 500, id, -32603, "Internal error");
            } else if (response.error() != null && response.error()
                .code() == -32603) {
                    rpcError(exchange, 200, id, -32603, "Internal error");
                } else {
                    sendJson(exchange, 200, response);
                }
        }

        private SessionState requireSession(HttpExchange exchange) throws IOException {
            String id = singleHeader(exchange.getRequestHeaders(), "Mcp-Session-Id");
            if (id == null) {
                httpError(exchange, 400, "Mcp-Session-Id is required after initialize");
                return null;
            }
            SessionState state = sessions.get(id);
            if (state == null || !state.touch()) {
                httpError(exchange, 404, "MCP session expired or unknown; initialize a new session");
                return null;
            }
            return state;
        }

        private void handleSessionHttp(HttpExchange exchange) throws Exception {
            if (closing) {
                httpError(exchange, 503, "Endpoint is stopping");
                return;
            }
            if (!PROTOCOL_VERSION.equals(singleHeader(exchange.getRequestHeaders(), "MCP-Protocol-Version"))) {
                httpError(exchange, 400, "MCP-Protocol-Version is required and must be supported");
                return;
            }
            SessionState state = requireSession(exchange);
            if (state == null) {
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                removeSession(state);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!accepts(singleHeader(exchange.getRequestHeaders(), "Accept"), "text/event-stream")) {
                httpError(exchange, 406, "Accept must include text/event-stream");
                return;
            }
            // No event IDs are emitted: delivery is a hint. Re-read resource/cursor after every reconnect.
            if (!eventStreams.tryAcquire()) {
                httpError(exchange, 429, "Too many event streams");
                return;
            }
            SseTransport transport = new SseTransport(exchange);
            McpStreamableServerSession.McpStreamableServerSessionStream stream = null;
            try {
                stream = state.openListeningStream(transport);
                if (stream == null) {
                    httpError(exchange, 404, "MCP session expired or unknown; initialize a new session");
                    return;
                }
                exchange.getResponseHeaders()
                    .set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders()
                    .set("Cache-Control", "no-store");
                exchange.getResponseHeaders()
                    .set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(200, 0);
                transport.write(
                    ": connected; re-read subscribed resources after reconnect\n\n".getBytes(StandardCharsets.UTF_8));
                while (!closing && !transport.closed.get()) {
                    byte[] frame = transport.queue.poll(limits.eventHeartbeatMillis, TimeUnit.MILLISECONDS);
                    if (!transport.closed.get()) {
                        transport.write(frame == null ? ": keepalive\n\n".getBytes(StandardCharsets.UTF_8) : frame);
                    }
                }
            } catch (IOException disconnected) {
                // Normal peer disconnect; SDK session survives until DELETE or idle expiry so a client can reconnect.
            } finally {
                if (stream != null) {
                    stream.releaseTransport();
                }
                transport.close();
                state.touch();
                eventStreams.release();
            }
        }

        private class ReplyTransport implements McpStreamableServerTransport {

            private final AtomicReference<McpSchema.JSONRPCResponse> response = new AtomicReference<>();

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return sendMessage(message, null);
            }

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
                return Mono.fromRunnable(() -> {
                    if (!(message instanceof McpSchema.JSONRPCResponse)
                        || !response.compareAndSet(null, (McpSchema.JSONRPCResponse) message)) {
                        throw new IllegalStateException("Only one JSON response is supported on this POST");
                    }
                });
            }

            @Override
            public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
                return jsonMapper.convertValue(value, type);
            }

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.empty();
            }
        }

        private final class SseTransport extends ReplyTransport {

            private final HttpExchange exchange;
            private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
            private final AtomicBoolean closed = new AtomicBoolean();

            private SseTransport(HttpExchange exchange) {
                this.exchange = exchange;
            }

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
                return Mono.fromRunnable(() -> {
                    if (closed.get()) {
                        return;
                    }
                    try {
                        byte[] frame = ("event: message\ndata: " + jsonMapper.writeValueAsString(message) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                        if (frame.length > limits.maxResponseBytes || !queue.offer(frame)) {
                            // A slow consumer reconnects and recovers using its message cursor; never block a game
                            // tick.
                            close();
                        }
                    } catch (IOException error) {
                        close();
                    }
                });
            }

            private void write(byte[] frame) throws IOException {
                ScheduledFuture<?> deadline = deadlines
                    .schedule(exchange::close, limits.requestTimeoutMillis, TimeUnit.MILLISECONDS);
                try {
                    exchange.getResponseBody()
                        .write(frame);
                    exchange.getResponseBody()
                        .flush();
                } finally {
                    deadline.cancel(false);
                }
            }

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.fromRunnable(() -> {
                    closed.set(true);
                    queue.clear();
                    queue.offer(new byte[0]);
                });
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

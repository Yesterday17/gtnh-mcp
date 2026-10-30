package io.github.gtnhmcp.config;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.gtnhmcp.LocalConfig;

/** Minecraft-independent listener transaction. All binding, file writes and shutdown run on one owned worker. */
public final class ServerLifecycle {

    public interface Endpoint {

        void start() throws Exception;

        void close();
    }

    @FunctionalInterface
    public interface EndpointFactory {

        Endpoint create(LocalConfig config) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigWriter {

        void save(LocalConfig config) throws Exception;
    }

    /** Safe for the UI: contains neither the private token nor endpoint methods that could wait on shutdown locks. */
    public static final class State {

        public final boolean enabled;
        public final int port;
        public final boolean running;
        public final boolean applying;
        public final String errorCode;

        private State(LocalConfig config, boolean running, boolean applying, String errorCode) {
            enabled = config.enabled;
            port = config.port;
            this.running = running;
            this.applying = applying;
            this.errorCode = errorCode;
        }
    }

    public static final class Result {

        public final boolean success;
        public final String errorCode;

        private Result(boolean success, String errorCode) {
            this.success = success;
            this.errorCode = errorCode;
        }
    }

    private final Object lock = new Object();
    private final EndpointFactory factory;
    private final ConfigWriter writer;
    private final ExecutorService worker;
    private LocalConfig config;
    private Endpoint active;
    private boolean busy;
    private boolean closed;
    private String errorCode;
    private CompletableFuture<Void> closing;
    private volatile State state;

    public ServerLifecycle(LocalConfig initial, EndpointFactory factory, ConfigWriter writer) {
        config = Objects.requireNonNull(initial, "initial");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.writer = Objects.requireNonNull(writer, "writer");
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "gtnhmcp-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        publish();
    }

    public State state() {
        return state;
    }

    /**
     * Starts the initially configured listener without rewriting its file. Disabled startup stays fully configurable.
     */
    public CompletableFuture<Result> start() {
        synchronized (lock) {
            return submit(config.enabled, config.port, false);
        }
    }

    public CompletableFuture<Result> apply(boolean enabled, int port) {
        synchronized (lock) {
            return submit(enabled, port, true);
        }
    }

    private CompletableFuture<Result> submit(boolean enabled, int port, boolean save) {
        if (closed) return CompletableFuture.completedFuture(new Result(false, "closed"));
        if (busy) return CompletableFuture.completedFuture(new Result(false, "busy"));
        LocalConfig candidate;
        try {
            candidate = config.withServerSettings(enabled, port);
        } catch (IllegalArgumentException invalid) {
            errorCode = "invalid_port";
            publish();
            return CompletableFuture.completedFuture(new Result(false, errorCode));
        }
        busy = true;
        errorCode = null;
        publish();
        CompletableFuture<Result> result = new CompletableFuture<>();
        worker.execute(() -> {
            Result applied;
            try {
                applied = transact(candidate, save);
            } catch (RuntimeException unexpected) {
                // Exception messages can contain implementation details. Only stable codes reach the screen.
                applied = new Result(false, "apply_failed");
            }
            synchronized (lock) {
                busy = false;
                errorCode = applied.errorCode;
                publish();
            }
            result.complete(applied);
        });
        return result;
    }

    private Result transact(LocalConfig candidate, boolean save) {
        Endpoint previous;
        LocalConfig previousConfig;
        synchronized (lock) {
            if (closed) return new Result(false, "closed");
            previous = active;
            previousConfig = config;
        }
        Endpoint replacement = previous;
        if (candidate.enabled && (previous == null || candidate.port != previousConfig.port)) {
            replacement = null;
            try {
                replacement = factory.create(candidate);
                replacement.start();
            } catch (Exception failure) {
                if (replacement != null) replacement.close();
                return new Result(false, "bind_failed");
            }
        } else if (!candidate.enabled) {
            replacement = null;
        }
        if (save && (candidate.enabled != previousConfig.enabled || candidate.port != previousConfig.port)) {
            try {
                writer.save(candidate);
            } catch (Exception failure) {
                if (replacement != null && replacement != previous) replacement.close();
                return new Result(false, "save_failed");
            }
        }
        synchronized (lock) {
            config = candidate;
            active = replacement;
            publish();
        }
        if (previous != null && previous != replacement) previous.close();
        return new Result(true, null);
    }

    /** Queues shutdown after any accepted transaction; callers may await this only outside the game thread. */
    public CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            if (closing != null) return closing;
            closed = true;
            closing = new CompletableFuture<>();
            worker.execute(() -> {
                Endpoint previous;
                synchronized (lock) {
                    previous = active;
                    active = null;
                    publish();
                }
                try {
                    if (previous != null) previous.close();
                    closing.complete(null);
                } catch (RuntimeException failure) {
                    closing.completeExceptionally(failure);
                }
            });
            worker.shutdown();
            return closing;
        }
    }

    private void publish() {
        state = new State(config, active != null, busy, errorCode);
    }
}

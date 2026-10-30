package io.github.gtnhmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import reactor.core.publisher.Mono;

/** Forces lifecycle interleavings with the real SDK session, instead of relying on probabilistic socket timing. */
class McpSessionLifecycleTest {

    @Test
    void deleteCannotFinishBetweenStreamAdmissionAndSdkRegistration() throws Exception {
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch resumeRegistration = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        McpStreamableServerSession sdk = new McpStreamableServerSession(
            "session",
            McpSchema.ClientCapabilities.builder()
                .build(),
            new McpSchema.Implementation("test", "1"),
            Duration.ofSeconds(1),
            Collections.emptyMap(),
            Collections.emptyMap(),
            () -> Mono.fromRunnable(() -> cleaned.set(true))) {

            @Override
            public McpStreamableServerSessionStream listeningStream(McpStreamableServerTransport transport) {
                registering.countDown();
                try {
                    if (!resumeRegistration.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Registration was not resumed");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return super.listeningStream(transport);
            }
        };
        McpEndpoint.SessionState state = new McpEndpoint.SessionState(sdk, new Object());
        FakeTransport transport = new FakeTransport();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> opened = workers.submit(() -> state.openListeningStream(transport));
            assertTrue(registering.await(1, TimeUnit.SECONDS));
            Future<?> deleted = workers.submit(() -> {
                closing.countDown();
                state.close();
            });
            assertTrue(closing.await(1, TimeUnit.SECONDS));
            assertFalse(deleted.isDone(), "DELETE must not finish before a previously admitted stream is registered");
            resumeRegistration.countDown();
            opened.get(2, TimeUnit.SECONDS);
            deleted.get(2, TimeUnit.SECONDS);
            assertTrue(cleaned.get());
            assertTrue(transport.closed.get());
            assertFalse(sdk.hasOpenStream(), "Deleted sessions must not retain an orphan event stream");
            assertNull(state.openListeningStream(new FakeTransport()));
            assertFalse(state.beginRequest());
        } finally {
            resumeRegistration.countDown();
            state.close();
            workers.shutdownNow();
        }
    }

    @Test
    void deletionDefersSdkCleanupUntilAlreadyAcceptedPostFinishes() {
        Set<String> subscriptions = new HashSet<>();
        AtomicBoolean cleaned = new AtomicBoolean();
        McpStreamableServerSession sdk = session(() -> {
            subscriptions.clear();
            cleaned.set(true);
        });
        McpEndpoint.SessionState state = new McpEndpoint.SessionState(sdk, new Object());
        FakeTransport transport = new FakeTransport();
        state.openListeningStream(transport);
        assertTrue(state.beginRequest());
        state.close();
        assertTrue(transport.closed.get(), "DELETE closes the listening socket without waiting for a slow POST");
        assertFalse(cleaned.get());
        assertFalse(state.beginRequest());
        assertNull(state.openListeningStream(new FakeTransport()));
        // A subscription handler admitted before DELETE can still complete, but cleanup must run after it.
        subscriptions.add("session");
        state.endRequest();
        assertTrue(cleaned.get());
        assertTrue(subscriptions.isEmpty());
        assertFalse(sdk.hasOpenStream());
    }

    @Test
    void expiryRechecksActiveRequestsAndStreamsUnderTheAdmissionLock() {
        AtomicBoolean cleaned = new AtomicBoolean();
        McpStreamableServerSession sdk = session(() -> cleaned.set(true));
        McpEndpoint.SessionState state = new McpEndpoint.SessionState(sdk, new Object());
        assertTrue(state.beginRequest());
        assertFalse(state.expireIfIdle(Long.MAX_VALUE), "A POST admitted before SDK stream creation is active");
        state.endRequest();
        state.openListeningStream(new FakeTransport());
        assertFalse(state.expireIfIdle(Long.MAX_VALUE));
        sdk.releaseListeningStream();
        assertTrue(state.touch());
        assertFalse(state.expireIfIdle(0), "Recent activity must be checked again during expiry");
        assertTrue(state.expireIfIdle(Long.MAX_VALUE));
        assertTrue(cleaned.get());
        assertFalse(state.beginRequest());
    }

    private static McpStreamableServerSession session(Runnable cleanup) {
        return new McpStreamableServerSession(
            "session",
            McpSchema.ClientCapabilities.builder()
                .build(),
            new McpSchema.Implementation("test", "1"),
            Duration.ofSeconds(1),
            Collections.emptyMap(),
            Collections.emptyMap(),
            () -> Mono.fromRunnable(cleanup));
    }

    private static final class FakeTransport implements McpStreamableServerTransport {

        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> closed.set(true));
        }
    }
}

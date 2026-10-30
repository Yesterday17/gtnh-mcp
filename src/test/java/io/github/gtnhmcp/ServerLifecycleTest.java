package io.github.gtnhmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.gtnhmcp.config.ServerLifecycle;

class ServerLifecycleTest {

    @TempDir
    Path temporary;

    @Test
    void disabledStartupCanEnableDisableAndEnableWithFreshEndpoints() throws Exception {
        LocalConfig config = LocalConfig.load(temporary)
            .withServerSettings(false, 29710);
        config.save();
        List<FakeEndpoint> endpoints = new ArrayList<>();
        ServerLifecycle controller = new ServerLifecycle(config, next -> {
            FakeEndpoint endpoint = new FakeEndpoint();
            endpoints.add(endpoint);
            return endpoint;
        }, LocalConfig::save);
        try {
            assertTrue(await(controller.start()).success);
            assertEquals(0, endpoints.size());
            assertFalse(controller.state().running);
            assertTrue(await(controller.apply(true, 29710)).success);
            assertEquals(1, endpoints.size());
            assertTrue(controller.state().running);
            assertTrue(await(controller.apply(false, 29710)).success);
            assertEquals(1, endpoints.get(0).closes.get());
            assertFalse(controller.state().running);
            assertFalse(LocalConfig.load(temporary).enabled);
            assertTrue(await(controller.apply(true, 29710)).success);
            assertEquals(2, endpoints.size());
            assertEquals(1, endpoints.get(1).starts.get());
            assertEquals(config.token, LocalConfig.load(temporary).token);
        } finally {
            CompletableFuture<Void> closed = controller.closeAsync();
            assertSame(closed, controller.closeAsync());
            closed.get(3, TimeUnit.SECONDS);
        }
        assertEquals(1, endpoints.get(1).closes.get());
    }

    @Test
    void bindAndSaveFailuresKeepTheWorkingEndpointAndPersistedConfiguration() throws Exception {
        LocalConfig initial = LocalConfig.load(temporary);
        AtomicBoolean failSave = new AtomicBoolean();
        List<FakeEndpoint> endpoints = new ArrayList<>();
        ServerLifecycle controller = new ServerLifecycle(initial, next -> {
            FakeEndpoint endpoint = new FakeEndpoint();
            endpoint.failStart = next.port == 31001;
            endpoints.add(endpoint);
            return endpoint;
        }, next -> {
            if (failSave.get()) throw new IOException("Simulated disk failure with sensitive details");
            next.save();
        });
        try {
            assertTrue(await(controller.start()).success);
            ServerLifecycle.Result bindFailure = await(controller.apply(true, 31001));
            assertFalse(bindFailure.success);
            assertEquals("bind_failed", bindFailure.errorCode);
            assertEquals(initial.port, controller.state().port);
            assertTrue(controller.state().running);
            assertEquals(0, endpoints.get(0).closes.get());
            assertEquals(1, endpoints.get(1).closes.get());
            failSave.set(true);
            ServerLifecycle.Result saveFailure = await(controller.apply(true, 31002));
            assertFalse(saveFailure.success);
            assertEquals("save_failed", saveFailure.errorCode);
            assertEquals(1, endpoints.get(2).closes.get());
            assertEquals(0, endpoints.get(0).closes.get());
            assertTrue(controller.state().running);
            assertEquals(initial.port, LocalConfig.load(temporary).port);
            assertEquals(initial.token, LocalConfig.load(temporary).token);
            assertFalse(await(controller.apply(false, initial.port)).success);
            assertTrue(controller.state().running);
            assertTrue(LocalConfig.load(temporary).enabled);
        } finally {
            controller.closeAsync()
                .get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void applyAndStateReadsDoNotWaitForSlowEndpointShutdown() throws Exception {
        LocalConfig initial = LocalConfig.load(temporary);
        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        FakeEndpoint previous = new FakeEndpoint() {

            @Override
            public void close() {
                stopping.countDown();
                try {
                    assertTrue(resume.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
                super.close();
            }
        };
        ServerLifecycle controller = new ServerLifecycle(
            initial,
            next -> next.port == initial.port ? previous : new FakeEndpoint(),
            LocalConfig::save);
        try {
            assertTrue(await(controller.start()).success);
            CompletableFuture<ServerLifecycle.Result> pending = controller.apply(true, 31002);
            assertTrue(stopping.await(2, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            assertTrue(controller.state().applying);
            assertEquals(31002, controller.state().port);
            assertEquals("busy", await(controller.apply(false, 31002)).errorCode);
            resume.countDown();
            assertTrue(await(pending).success);
            assertTrue(previous.stopThread.startsWith("gtnhmcp-lifecycle"));
        } finally {
            resume.countDown();
            controller.closeAsync()
                .get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownDuringAnAcceptedChangeClosesBothEndpointsExactlyOnce() throws Exception {
        LocalConfig initial = LocalConfig.load(temporary);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        FakeEndpoint previous = new FakeEndpoint();
        FakeEndpoint replacement = new FakeEndpoint() {

            @Override
            public void start() throws Exception {
                starting.countDown();
                assertTrue(resume.await(3, TimeUnit.SECONDS));
                super.start();
            }
        };
        ServerLifecycle controller = new ServerLifecycle(
            initial,
            next -> next.port == initial.port ? previous : replacement,
            LocalConfig::save);
        try {
            assertTrue(await(controller.start()).success);
            CompletableFuture<ServerLifecycle.Result> pending = controller.apply(true, 31002);
            assertTrue(starting.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> closing = controller.closeAsync();
            assertEquals("closed", await(controller.apply(true, 31003)).errorCode);
            resume.countDown();
            assertTrue(await(pending).success);
            closing.get(3, TimeUnit.SECONDS);
            assertEquals(1, previous.closes.get());
            assertEquals(1, replacement.closes.get());
            assertFalse(controller.state().running);
        } finally {
            resume.countDown();
            controller.closeAsync()
                .get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidPortDoesNotBindOrSaveAndUnchangedSettingsDoNotRecreateListener() throws Exception {
        LocalConfig initial = LocalConfig.load(temporary);
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        ServerLifecycle controller = new ServerLifecycle(initial, next -> {
            creations.incrementAndGet();
            return new FakeEndpoint();
        }, next -> saves.incrementAndGet());
        try {
            assertTrue(await(controller.start()).success);
            assertEquals("invalid_port", await(controller.apply(true, 80)).errorCode);
            assertEquals("invalid_port", await(controller.apply(true, 65536)).errorCode);
            assertTrue(await(controller.apply(true, initial.port)).success);
            assertEquals(1, creations.get());
            assertEquals(0, saves.get());
            assertEquals(initial.port, controller.state().port);
        } finally {
            controller.closeAsync()
                .get(3, TimeUnit.SECONDS);
        }
    }

    private static ServerLifecycle.Result await(CompletableFuture<ServerLifecycle.Result> result) throws Exception {
        return result.get(4, TimeUnit.SECONDS);
    }

    private static class FakeEndpoint implements ServerLifecycle.Endpoint {

        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        boolean failStart;
        volatile String stopThread;

        @Override
        public void start() throws Exception {
            starts.incrementAndGet();
            if (failStart) throw new BindException("Simulated occupied port");
        }

        @Override
        public void close() {
            stopThread = Thread.currentThread()
                .getName();
            closes.incrementAndGet();
        }
    }
}

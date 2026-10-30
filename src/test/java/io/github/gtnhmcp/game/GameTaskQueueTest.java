package io.github.gtnhmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class GameTaskQueueTest {

    @Test
    void timeoutCannotExecuteLaterOnGameThread() throws Exception {
        GameTaskQueue queue = new GameTaskQueue(2);
        AtomicBoolean ran = new AtomicBoolean();
        assertThrows(TimeoutException.class, () -> queue.submitAndWait(() -> {
            ran.set(true);
            return 1;
        }, 10));
        queue.drain(2, Long.MAX_VALUE);
        assertFalse(ran.get());
        assertEquals(0, queue.size());
    }

    @Test
    void executionUsesDrainingThreadAndInvalidationCancelsPendingWork() throws Exception {
        GameTaskQueue queue = new GameTaskQueue(2);
        CompletableFuture<Thread> result = CompletableFuture.supplyAsync(() -> {
            try {
                return queue.submitAndWait(Thread::currentThread, 2000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        waitForPending(queue);
        Thread owner = Thread.currentThread();
        queue.drain(1, Long.MAX_VALUE);
        assertEquals(owner, result.get(2, TimeUnit.SECONDS));

        AtomicBoolean ran = new AtomicBoolean();
        CompletableFuture<String> cancelled = CompletableFuture.supplyAsync(() -> {
            try {
                queue.submitAndWait(() -> ran.compareAndSet(false, true), 2000);
                return "unexpected success";
            } catch (Exception e) {
                return e.getMessage();
            }
        });
        waitForPending(queue);
        queue.invalidate();
        queue.drain(1, Long.MAX_VALUE);
        assertTrue(
            cancelled.get(2, TimeUnit.SECONDS)
                .startsWith("world_changed"));
        assertFalse(ran.get());
    }

    @Test
    void cancellingNetworkCallerNeverInterruptsGameThread() throws Exception {
        GameTaskQueue queue = new GameTaskQueue(2);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                queue.submitAndWait(() -> {
                    running.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                    }
                    return 1;
                }, 2000);
            } catch (Exception ignored) {}
        });
        caller.start();
        waitForPending(queue);
        Thread game = new Thread(() -> queue.drain(1, Long.MAX_VALUE));
        game.start();
        assertTrue(running.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1000);
        release.countDown();
        game.join(2000);
        assertFalse(interrupted.get());
        assertFalse(game.isAlive());
    }

    private static void waitForPending(GameTaskQueue queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (queue.size() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(queue.size() > 0);
    }
}

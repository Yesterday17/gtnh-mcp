package io.github.gtnhmcp.game;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.gtnhmcp.protocol.RequestDeadline;

/** Network workers wait here; the game thread only drains and never waits. */
public final class GameTaskQueue {

    private final ArrayBlockingQueue<FutureTask<?>> pending;

    public GameTaskQueue(int capacity) {
        pending = new ArrayBlockingQueue<>(capacity);
    }

    public <T> T submitAndWait(Callable<T> action, long timeoutMillis) throws Exception {
        final long deadline = RequestDeadline
            .constrain(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        if (System.nanoTime() >= deadline) throw new TimeoutException("Request expired before game queue submission");
        FutureTask<T> task = new FutureTask<>(() -> {
            if (System.nanoTime() >= deadline) throw new TimeoutException("Request expired before game tick");
            return action.call();
        });
        if (!pending.offer(task)) throw new IllegalStateException("busy: game request queue is full");
        try {
            return task.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Game task failed", cause);
        } catch (CancellationException e) {
            throw new IllegalStateException("world_changed: game context changed before request completed", e);
        } finally {
            // Never interrupt Minecraft's tick thread, even if the HTTP caller disconnects.
            task.cancel(false);
            pending.remove(task);
        }
    }

    public void drain(int maxTasks, long budgetNanos) {
        long start = System.nanoTime();
        for (int i = 0; i < maxTasks; i++) {
            FutureTask<?> task = pending.poll();
            if (task == null) return;
            task.run();
            if (System.nanoTime() - start >= budgetNanos) return;
        }
    }

    public void invalidate() {
        FutureTask<?> task;
        while ((task = pending.poll()) != null) task.cancel(false);
    }

    public int size() {
        return pending.size();
    }
}

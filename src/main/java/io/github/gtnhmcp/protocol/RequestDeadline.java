package io.github.gtnhmcp.protocol;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

/** Propagates the absolute HTTP/tool deadline into synchronous game-queue submissions on an MCP worker. */
public final class RequestDeadline {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private RequestDeadline() {}

    /** Game queues must use the earlier deadline, including when timeout cancellation has not been scheduled yet. */
    public static long constrain(long requestedDeadlineNanos) {
        Long inherited = CURRENT.get();
        return inherited == null ? requestedDeadlineNanos : Math.min(inherited, requestedDeadlineNanos);
    }

    static <T> T call(long deadlineNanos, Callable<T> action) throws Exception {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TimeoutException("Request deadline elapsed before tool execution");
        }
        Long previous = CURRENT.get();
        CURRENT.set(previous == null ? deadlineNanos : Math.min(previous, deadlineNanos));
        try {
            return action.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}

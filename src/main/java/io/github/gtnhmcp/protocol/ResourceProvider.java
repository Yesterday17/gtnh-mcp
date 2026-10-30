package io.github.gtnhmcp.protocol;

import java.util.Map;
import java.util.function.Consumer;

/** JSON-only, thread-safe snapshots and non-blocking change signals for the active world. */
public interface ResourceProvider {

    String MESSAGES_URI = "gtnh://context/messages";
    String PROJECT_URI = "gtnh://context/project";

    Map<String, Object> readResource(String uri) throws Exception;

    /** The registration must be removable; listeners only enqueue notifications, never perform network I/O. */
    AutoCloseable onResourceChanged(Consumer<String> listener);
}

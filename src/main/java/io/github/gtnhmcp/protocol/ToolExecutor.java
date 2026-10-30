package io.github.gtnhmcp.protocol;

import java.util.Map;

/**
 * Runs on a bounded MCP worker, never on a Minecraft thread. Implementations must enqueue any game reads on the
 * appropriate game thread, respect interruption/deadlines, and return detached JSON-compatible data.
 */
@FunctionalInterface
public interface ToolExecutor {

    Map<String, Object> execute(String name, Map<String, Object> arguments) throws Exception;
}

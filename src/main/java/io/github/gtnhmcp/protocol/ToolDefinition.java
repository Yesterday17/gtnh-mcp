package io.github.gtnhmcp.protocol;

import java.util.Map;
import java.util.Objects;

/** A Minecraft-independent tool description. Schemas contain JSON values only. */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final boolean readOnly;

    public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, true);
    }

    public ToolDefinition(String name, String description, Map<String, Object> inputSchema, boolean readOnly) {
        this.readOnly = readOnly;
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException(
                "Tool name must contain 1-64 letters, numbers, dots, dashes or underscores");
        }
        this.name = name;
        this.description = Objects.requireNonNull(description, "description");
        this.inputSchema = JsonValues.copyMap(Objects.requireNonNull(inputSchema, "inputSchema"));
        if (!"object".equals(this.inputSchema.get("type"))) {
            throw new IllegalArgumentException("Tool input schema must describe an object");
        }
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }
}

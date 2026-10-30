package io.github.gtnhmcp.protocol;

/** An expected, safe-to-display tool failure. Never put credentials or internal exception details in its message. */
public final class ToolExecutionException extends Exception {

    private static final long serialVersionUID = 1L;
    private final String code;

    public ToolExecutionException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid tool error code");
        }
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

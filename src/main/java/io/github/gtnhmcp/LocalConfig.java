package io.github.gtnhmcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/** One private token per Minecraft instance. Never written to logs or tool output. */
public final class LocalConfig {

    public final boolean enabled;
    public final int port;
    public final String token;
    public final Path path;

    private LocalConfig(boolean enabled, int port, String token, Path path) {
        this.enabled = enabled;
        this.port = port;
        this.token = token;
        this.path = path;
    }

    public static LocalConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("gtnhmcp.properties");
        Properties p = new Properties();
        boolean fresh = !Files.exists(path);
        if (!fresh) {
            try (InputStream input = Files.newInputStream(path)) {
                p.load(input);
            }
        }
        if (fresh) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            p.setProperty("enabled", "true");
            p.setProperty("port", "29710");
            p.setProperty(
                "token",
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(secret));
            Files.createFile(path);
            restrictPermissions(path);
            try (OutputStream output = Files.newOutputStream(path)) {
                p.store(output, "GTNH MCP local read-only server. Keep token private. Restart the game after changes.");
            }
        }
        restrictPermissions(path);
        int port;
        try {
            port = Integer.parseInt(p.getProperty("port", "29710"));
        } catch (NumberFormatException e) {
            throw new IOException("gtnhmcp.properties port must be a number", e);
        }
        if (port < 1024 || port > 65535) throw new IOException("gtnhmcp.properties port must be 1024..65535");
        String token = p.getProperty("token", "")
            .trim();
        if (token.length() < 32) throw new IOException("gtnhmcp.properties token must contain at least 32 characters");
        String enabled = p.getProperty("enabled", "true");
        if (!"true".equalsIgnoreCase(enabled) && !"false".equalsIgnoreCase(enabled)) {
            throw new IOException("gtnhmcp.properties enabled must be true or false");
        }
        return new LocalConfig(Boolean.parseBoolean(enabled), port, token, path);
    }

    private static void restrictPermissions(Path path) throws IOException {
        if (Files.getFileStore(path)
            .supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}

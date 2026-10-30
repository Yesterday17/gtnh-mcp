package io.github.gtnhmcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/** One private token per Minecraft instance. Never written to logs, GUI text or tool output. */
public final class LocalConfig {

    public final boolean enabled;
    public final int port;
    public final String token;
    public final Path path;
    private final Properties properties;

    private LocalConfig(boolean enabled, int port, String token, Path path, Properties properties) {
        this.enabled = enabled;
        this.port = port;
        this.token = token;
        this.path = path;
        this.properties = new Properties();
        this.properties.putAll(properties);
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
        } else {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            p.setProperty("enabled", "true");
            p.setProperty("port", "29710");
            p.setProperty(
                "token",
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(secret));
        }
        int port;
        try {
            port = Integer.parseInt(
                p.getProperty("port", "29710")
                    .trim());
        } catch (NumberFormatException e) {
            throw new IOException("gtnhmcp.properties port must be a number", e);
        }
        if (port < 1024 || port > 65535) throw new IOException("gtnhmcp.properties port must be 1024..65535");
        String token = p.getProperty("token", "")
            .trim();
        if (!token.matches("[A-Za-z0-9._~-]{32,512}")) {
            throw new IOException("gtnhmcp.properties requires a valid 32-512 character local token");
        }
        String enabled = p.getProperty("enabled", "true")
            .trim();
        if (!"true".equalsIgnoreCase(enabled) && !"false".equalsIgnoreCase(enabled)) {
            throw new IOException("gtnhmcp.properties enabled must be true or false");
        }
        LocalConfig config = new LocalConfig(Boolean.parseBoolean(enabled), port, token, path, p);
        if (fresh) config.save();
        else restrictPermissions(path);
        return config;
    }

    /** Edits only the public listener settings. The private token and unknown properties are preserved. */
    public LocalConfig withServerSettings(boolean enabled, int port) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Port must be 1024..65535");
        return new LocalConfig(enabled, port, token, path, properties);
    }

    /** Replace the whole private file atomically; a failed write leaves the previous configuration intact. */
    public void save() throws IOException {
        Properties updated = new Properties();
        updated.putAll(properties);
        updated.setProperty("enabled", Boolean.toString(enabled));
        updated.setProperty("port", Integer.toString(port));
        updated.setProperty("token", token);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        updated.store(
            bytes,
            "GTNH MCP local server. Keep token private. Listener settings can be changed in Mods > Config.");
        Path directory = path.toAbsolutePath()
            .getParent();
        Path temporary;
        if (Files.getFileStore(directory)
            .supportsFileAttributeView("posix")) {
            temporary = Files.createTempFile(
                directory,
                "gtnhmcp-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            temporary = Files.createTempFile(directory, "gtnhmcp-", ".tmp");
        }
        boolean committed = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Do not silently fall back to a non-atomic replacement when the filesystem cannot provide this contract.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            committed = true;
        } finally {
            if (!committed) Files.deleteIfExists(temporary);
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        if (Files.getFileStore(path)
            .supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}

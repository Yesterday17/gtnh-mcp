package io.github.gtnhmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalConfigTest {

    @TempDir
    Path temporary;

    @Test
    void instanceTokenIsPrivatePersistentAndIndependent() throws Exception {
        LocalConfig first = LocalConfig.load(temporary.resolve("first"));
        assertTrue(first.token.length() >= 32);
        assertEquals(first.token, LocalConfig.load(temporary.resolve("first")).token);
        assertNotEquals(first.token, LocalConfig.load(temporary.resolve("second")).token);
        if (Files.getFileStore(first.path)
            .supportsFileAttributeView("posix")) {
            assertEquals(
                "rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(first.path)));
        }
    }

    @Test
    void incompleteExistingConfigurationFailsClosed() throws Exception {
        Files.write(
            temporary.resolve("gtnhmcp.properties"),
            "enabled=true\nport=29710\ntoken=\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> LocalConfig.load(temporary));
    }
}

package io.github.gtnhmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void savingSettingsPreservesTokenUnknownPropertiesAndPrivatePermissions() throws Exception {
        LocalConfig original = LocalConfig.load(temporary);
        Files.writeString(original.path, Files.readString(original.path) + "customOption=keep-this\n");
        LocalConfig changed = LocalConfig.load(temporary)
            .withServerSettings(false, 31002);
        changed.save();
        LocalConfig restored = LocalConfig.load(temporary);
        assertFalse(restored.enabled);
        assertEquals(31002, restored.port);
        assertEquals(original.token, restored.token);
        assertTrue(
            Files.readString(restored.path)
                .contains("customOption=keep-this"));
        try (java.util.stream.Stream<Path> files = Files.list(temporary)) {
            assertEquals(1, files.count(), "Private temporary files must be removed after committing");
        }
        if (Files.getFileStore(restored.path)
            .supportsFileAttributeView("posix")) {
            assertEquals(
                "rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(restored.path)));
        }
    }

    @Test
    void invalidSettingsAndMalformedTokensDoNotRewriteExistingFile() throws Exception {
        LocalConfig original = LocalConfig.load(temporary);
        String before = Files.readString(original.path);
        assertThrows(IllegalArgumentException.class, () -> original.withServerSettings(true, 1023));
        assertEquals(before, Files.readString(original.path));
        String malformed = "enabled=false\nport=29710\ntoken=" + "!".repeat(40) + "\n";
        Files.writeString(original.path, malformed);
        assertThrows(IOException.class, () -> LocalConfig.load(temporary));
        assertEquals(malformed, Files.readString(original.path));
    }

    @Test
    void incompleteExistingConfigurationFailsClosed() throws Exception {
        Files.write(
            temporary.resolve("gtnhmcp.properties"),
            "enabled=true\nport=29710\ntoken=\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> LocalConfig.load(temporary));
    }
}

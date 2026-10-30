package io.github.gtnhmcp.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldIdentityTest {

    @TempDir
    Path temporary;

    @Test
    void identitySurvivesReentryAndRenameButNotAReplacementWorld() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("World"));
        String first = WorldIdentity.loadOrCreate(original);
        assertEquals(
            first,
            UUID.fromString(first)
                .toString());
        assertEquals(first, WorldIdentity.loadOrCreate(original));
        Path renamed = Files.move(original, temporary.resolve("Renamed"));
        assertEquals(first, WorldIdentity.loadOrCreate(renamed));
        Path replacement = Files.createDirectory(original);
        String replacementId = WorldIdentity.loadOrCreate(replacement);
        assertNotEquals(first, replacementId);
        Files.delete(replacement.resolve(".gtnhmcp-world-id"));
        Files.delete(replacement);
        assertNotEquals(replacementId, WorldIdentity.loadOrCreate(Files.createDirectory(original)));
    }

    @Test
    void corruptExistingIdentityIsNeverReplaced() throws Exception {
        Path world = Files.createDirectory(temporary.resolve("World"));
        Path marker = world.resolve(".gtnhmcp-world-id");
        for (String corrupt : List.of("", "not-a-uuid", "1-1-1-1-1", "x".repeat(100))) {
            Files.writeString(marker, corrupt);
            assertThrows(IOException.class, () -> WorldIdentity.loadOrCreate(world));
            assertEquals(corrupt, Files.readString(marker));
        }
    }

    @Test
    void aMissingSaveDirectoryIsNotCreated() {
        assertThrows(IOException.class, () -> WorldIdentity.loadOrCreate(temporary.resolve("missing")));
    }

    @Test
    void anIdentityCopiedWithItsSaveKeepsTheSameContext() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("Original"));
        String first = WorldIdentity.loadOrCreate(original);
        Path copied = Files.createDirectory(temporary.resolve("Copied"));
        Files.copy(original.resolve(".gtnhmcp-world-id"), copied.resolve(".gtnhmcp-world-id"));
        assertEquals(first, WorldIdentity.loadOrCreate(copied));
    }
}

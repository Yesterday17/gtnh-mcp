package io.github.gtnhmcp.context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** An identity that follows the save directory, independently of its display name or folder name. */
public final class WorldIdentity {

    private static final String FILE_NAME = ".gtnhmcp-world-id";

    private WorldIdentity() {}

    /**
     * Creates only a local identity marker, never game world data. Renaming/moving the entire directory preserves this
     * identity; copying a save with its marker intentionally copies its planning identity too. Existing corrupt markers
     * are preserved and rejected rather than silently assigning a different world identity.
     */
    public static synchronized String loadOrCreate(Path saveDirectory) throws IOException {
        Path directory = saveDirectory.toRealPath();
        if (!Files.isDirectory(directory)) throw new IOException("The save directory does not exist");
        Path marker = directory.resolve(FILE_NAME);
        String created = UUID.randomUUID()
            .toString();
        try (FileChannel channel = FileChannel
            .open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap((created + "\n").getBytes(StandardCharsets.US_ASCII));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
            return created;
        } catch (FileAlreadyExistsException existing) {
            return readExisting(marker);
        }
    }

    private static String readExisting(Path marker) throws IOException {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The existing world identity marker is not a regular file");
        }
        String value;
        try (InputStream stream = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(80);
            if (bytes.length >= 80) throw new IOException("The world identity marker is too long");
            value = new String(bytes, StandardCharsets.US_ASCII).trim();
        }
        try {
            String canonical = UUID.fromString(value)
                .toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The existing world identity marker is invalid; it has not been replaced", invalid);
        }
    }
}

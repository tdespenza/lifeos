package com.lifeos.media.storage;

import com.lifeos.media.config.MediaProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Safe local-development object store. It streams and validates source bytes into generated
 * staging paths, atomically promotes them, and exposes HLS only from an external worker-created
 * private directory. It never transcodes or accepts a client-controlled path.
 */
@Component
@ConditionalOnProperty(
        name = "media.storage.mode",
        havingValue = "LOCAL_DEVELOPMENT",
        matchIfMissing = true)
public class LocalDevelopmentMediaObjectStore implements MediaObjectStore {

    private static final int BUFFER_SIZE = 16_384;
    private static final int PREFIX_SIZE = 16;
    private static final long MAX_MANIFEST_BYTES = 1_048_576L;
    private static final long MAX_SEGMENT_BYTES = 26_214_400L;

    private final Clock clock;
    private final Path stagingRoot;
    private final Path sourceRoot;
    private final Path hlsRoot;

    public LocalDevelopmentMediaObjectStore(MediaProperties properties, Clock mediaClock) {
        clock = mediaClock;
        Path root = properties.getStorage().localRootPath();
        stagingRoot = requireDirectory(root.resolve("staging"));
        sourceRoot = requireDirectory(root.resolve("source"));
        hlsRoot = requireDirectory(root.resolve("hls"));
    }

    @Override
    public StagedMediaObject stage(InputStream content, MediaContentType contentType, long maximumBytes, Duration deadline) {
        if (content == null || maximumBytes < 1 || deadline == null || deadline.isNegative() || deadline.isZero()) {
            throw new MediaObjectStorageException();
        }
        UUID stagingId = UUID.randomUUID();
        Path staging = stagingPath(stagingId);
        Instant deadlineAt = clock.instant().plus(deadline);
        long length = 0L;
        try (InputStream input = content;
                OutputStream output = Files.newOutputStream(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] prefix = input.readNBytes(PREFIX_SIZE);
            checkDeadline(deadlineAt);
            validateSignature(contentType, prefix);
            length = write(prefix, output, digest, length, maximumBytes, deadlineAt);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                length = write(buffer, read, output, digest, length, maximumBytes, deadlineAt);
            }
            if (length == 0L) {
                throw new UnsupportedMediaContentException();
            }
            output.flush();
            return new StagedMediaObject(stagingId, HexFormat.of().formatHex(digest.digest()), length, contentType);
        } catch (MediaUploadTooLargeException | MediaUploadDeadlineExceededException | UnsupportedMediaContentException exception) {
            deleteQuietly(staging);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(staging);
            throw new MediaObjectStorageException(exception);
        } catch (RuntimeException exception) {
            deleteQuietly(staging);
            throw exception;
        }
    }

    @Override
    public StoredMediaObject promote(StagedMediaObject staged, UUID assetId) {
        if (staged == null || assetId == null) {
            throw new MediaObjectStorageException();
        }
        Path source = stagingPath(staged.stagingId());
        Path assetDirectory = sourceDirectory(assetId);
        Path target = safeChild(assetDirectory, staged.stagingId() + ".source");
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new MediaObjectStorageException();
            }
            Files.createDirectories(assetDirectory);
            moveAtomically(source, target);
            return new StoredMediaObject("local:" + assetId + ":" + staged.stagingId());
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    @Override
    public void discard(StagedMediaObject staged) {
        if (staged != null) {
            delete(stagingPath(staged.stagingId()));
        }
    }

    @Override
    public void deleteSource(String objectReference) {
        SourceReference parsed = SourceReference.parse(objectReference);
        delete(sourcePath(parsed));
        try {
            Files.deleteIfExists(sourceDirectory(parsed.assetId()));
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    /** Returns a generated source path for the opt-in local processing adapter. */
    public Path sourcePathForProcessing(String objectReference) {
        Path source = sourcePath(SourceReference.parse(objectReference));
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new MediaObjectStorageException();
        }
        return source;
    }

    /** Creates a private generated HLS staging directory below the local HLS root. */
    public Path createHlsProcessingDirectory(UUID assetId) {
        if (assetId == null) {
            throw new MediaObjectStorageException();
        }
        Path staging = safeChild(hlsRoot, assetId + ".processing-" + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            return staging;
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    /** Validates and atomically promotes one worker-created HLS directory. */
    public String promoteHlsDirectory(UUID assetId, Path stagingDirectory) {
        if (assetId == null || stagingDirectory == null || !stagingDirectory.startsWith(hlsRoot)) {
            throw new MediaObjectStorageException();
        }
        Path target = hlsDirectory(assetId);
        try {
            if (!Files.isDirectory(stagingDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(stagingDirectory.resolve("master.m3u8"), LinkOption.NOFOLLOW_LINKS)) {
                throw new MediaObjectStorageException();
            }
            validateHlsDirectory(stagingDirectory);
            moveAtomically(stagingDirectory, target);
            return "local-hls:" + assetId;
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    private static void validateHlsDirectory(Path directory) throws IOException {
        boolean hasSegment = false;
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.getParent().equals(directory)) {
                    throw new MediaObjectStorageException();
                }
                String filename = path.getFileName().toString();
                long size = Files.size(path);
                if (filename.equals("master.m3u8")) {
                    if (size < 1 || size > MAX_MANIFEST_BYTES) {
                        throw new MediaObjectStorageException();
                    }
                } else if (filename.matches("segment-[0-9]{5}\\.(ts|m4s)")) {
                    if (size < 1 || size > MAX_SEGMENT_BYTES) {
                        throw new MediaObjectStorageException();
                    }
                    hasSegment = true;
                } else {
                    throw new MediaObjectStorageException();
                }
            }
        }
        if (!hasSegment) {
            throw new MediaObjectStorageException();
        }
    }

    @Override
    public MediaReadObject openHlsManifest(String manifestReference) {
        UUID assetId = HlsReference.parse(manifestReference).assetId();
        return openRegularFile(safeChild(hlsDirectory(assetId), "master.m3u8"), MAX_MANIFEST_BYTES, "application/vnd.apple.mpegurl");
    }

    @Override
    public MediaReadObject openHlsSegment(String manifestReference, String segmentName) {
        UUID assetId = HlsReference.parse(manifestReference).assetId();
        if (segmentName == null
                || !segmentName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || segmentName.contains("..")
                || !(segmentName.endsWith(".m4s") || segmentName.endsWith(".ts"))) {
            throw new MediaHlsNotReadyException();
        }
        String contentType = segmentName.endsWith(".m4s") ? "video/iso.segment" : "video/mp2t";
        return openRegularFile(safeChild(hlsDirectory(assetId), segmentName), MAX_SEGMENT_BYTES, contentType);
    }

    private MediaReadObject openRegularFile(Path path, long maxBytes, String contentType) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MediaHlsNotReadyException();
            }
            long size = Files.size(path);
            if (size < 1 || size > maxBytes) {
                throw new MediaHlsNotReadyException();
            }
            return new MediaReadObject(Files.newInputStream(path, StandardOpenOption.READ), size, contentType);
        } catch (MediaHlsNotReadyException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    private static long write(
            byte[] bytes,
            OutputStream output,
            MessageDigest digest,
            long currentLength,
            long maximumBytes,
            Instant deadlineAt,
            Clock clock)
            throws IOException {
        return write(bytes, bytes.length, output, digest, currentLength, maximumBytes, deadlineAt, clock);
    }

    private long write(
            byte[] bytes,
            OutputStream output,
            MessageDigest digest,
            long currentLength,
            long maximumBytes,
            Instant deadlineAt)
            throws IOException {
        return write(bytes, output, digest, currentLength, maximumBytes, deadlineAt, clock);
    }

    private static long write(
            byte[] bytes,
            int length,
            OutputStream output,
            MessageDigest digest,
            long currentLength,
            long maximumBytes,
            Instant deadlineAt,
            Clock clock)
            throws IOException {
        checkDeadline(deadlineAt, clock);
        long next = currentLength + length;
        if (next > maximumBytes) {
            throw new MediaUploadTooLargeException();
        }
        output.write(bytes, 0, length);
        digest.update(bytes, 0, length);
        return next;
    }

    private long write(
            byte[] bytes,
            int length,
            OutputStream output,
            MessageDigest digest,
            long currentLength,
            long maximumBytes,
            Instant deadlineAt)
            throws IOException {
        return write(bytes, length, output, digest, currentLength, maximumBytes, deadlineAt, clock);
    }

    private void checkDeadline(Instant deadlineAt) {
        checkDeadline(deadlineAt, clock);
    }

    private static void checkDeadline(Instant deadlineAt, Clock clock) {
        if (clock.instant().isAfter(deadlineAt)) {
            throw new MediaUploadDeadlineExceededException();
        }
    }

    private static void validateSignature(MediaContentType contentType, byte[] prefix) {
        boolean valid = switch (contentType) {
            case MP4 -> prefix.length >= 8
                    && prefix[4] == 'f'
                    && prefix[5] == 't'
                    && prefix[6] == 'y'
                    && prefix[7] == 'p';
            case WEBM -> prefix.length >= 4
                    && prefix[0] == (byte) 0x1a
                    && prefix[1] == (byte) 0x45
                    && prefix[2] == (byte) 0xdf
                    && prefix[3] == (byte) 0xa3;
        };
        if (!valid) {
            throw new UnsupportedMediaContentException();
        }
    }

    private static Path requireDirectory(Path path) {
        try {
            Files.createDirectories(path);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MediaObjectStorageException();
            }
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    private Path stagingPath(UUID stagingId) {
        return safeChild(stagingRoot, stagingId + ".part");
    }

    private Path sourceDirectory(UUID assetId) {
        return safeChild(sourceRoot, assetId.toString());
    }

    private Path sourcePath(SourceReference reference) {
        return safeChild(sourceDirectory(reference.assetId()), reference.stagingId() + ".source");
    }

    private Path hlsDirectory(UUID assetId) {
        return safeChild(hlsRoot, assetId.toString());
    }

    private static Path safeChild(Path parent, String name) {
        Path child = parent.resolve(name).normalize();
        if (!child.startsWith(parent) || child.getParent() == null || !child.getParent().equals(parent)) {
            throw new MediaObjectStorageException();
        }
        return child;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    /** Removes a failed local processing directory without following symlinks. */
    public void discardHlsProcessingDirectory(Path directory) {
        if (directory == null || !directory.startsWith(hlsRoot)) {
            return;
        }
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(LocalDevelopmentMediaObjectStore::deleteQuietly);
                }
            }
        } catch (IOException ignored) {
            // Failed processing is already represented by a safe lifecycle code.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The primary storage failure remains the only safe caller-facing fact.
        }
    }

    private record SourceReference(UUID assetId, UUID stagingId) {

        private static SourceReference parse(String value) {
            if (value == null || !value.matches("local:[0-9a-f-]{36}:[0-9a-f-]{36}")) {
                throw new MediaObjectStorageException();
            }
            String[] parts = value.split(":", -1);
            try {
                return new SourceReference(UUID.fromString(parts[1]), UUID.fromString(parts[2]));
            } catch (IllegalArgumentException exception) {
                throw new MediaObjectStorageException(exception);
            }
        }
    }

    private record HlsReference(UUID assetId) {

        private static HlsReference parse(String value) {
            if (value == null || !value.matches("local-hls:[0-9a-f-]{36}")) {
                throw new MediaHlsNotReadyException();
            }
            try {
                return new HlsReference(UUID.fromString(value.substring("local-hls:".length())));
            } catch (IllegalArgumentException exception) {
                throw new MediaHlsNotReadyException();
            }
        }
    }
}

package com.lifeos.documentvault.storage;

import com.lifeos.documentvault.config.DocumentVaultStorageProperties;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Safe local-development object store. It uses generated UUID paths only, streams into a private
 * staging directory, verifies type/size/checksum, atomically promotes, and cleans every failed
 * temporary object. It is intentionally not a production cloud-object-store adapter.
 */
@Component
@ConditionalOnProperty(
        name = "document-vault.storage.mode",
        havingValue = "LOCAL_DEVELOPMENT",
        matchIfMissing = true)
public class LocalDocumentObjectStore implements DocumentObjectStore {

    private static final int BUFFER_SIZE = 16_384;
    private static final int PREFIX_SIZE = 16;
    private static final int MAX_SEARCHABLE_BYTES = 65_536;

    private final Clock clock;
    private final DocumentOcrExtractor ocrExtractor;
    private final Path stagingRoot;
    private final Path objectRoot;

    public LocalDocumentObjectStore(DocumentVaultStorageProperties properties, Clock documentVaultClock) {
        this(properties, documentVaultClock, DocumentOcrExtractor.disabled());
    }

    @Autowired
    public LocalDocumentObjectStore(
            DocumentVaultStorageProperties properties,
            Clock documentVaultClock,
            DocumentOcrExtractor ocrExtractor) {
        this.clock = documentVaultClock;
        this.ocrExtractor = ocrExtractor;
        Path root = properties.localRootPath();
        stagingRoot = requireDirectory(root.resolve("staging"));
        objectRoot = requireDirectory(root.resolve("objects"));
    }

    @Override
    public StagedDocumentObject stage(
            InputStream content, DocumentContentType contentType, long maxBytes, Duration deadline) {
        if (content == null || maxBytes < 1 || deadline == null || deadline.isNegative() || deadline.isZero()) {
            throw new DocumentObjectStorageException();
        }
        UUID stagingId = UUID.randomUUID();
        Path stagingPath = stagingPath(stagingId);
        long contentLength = 0L;
        ByteArrayOutputStream searchable = contentType.isTextLike()
                ? new ByteArrayOutputStream(Math.min(MAX_SEARCHABLE_BYTES, 8_192))
                : null;
        Instant deadlineAt = clock.instant().plus(deadline);
        try (InputStream input = content;
                OutputStream output = Files.newOutputStream(
                        stagingPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] prefix = input.readNBytes(PREFIX_SIZE);
            checkDeadline(deadlineAt);
            validateSignature(contentType, prefix);
            appendSearchable(searchable, prefix);
            contentLength = write(prefix, output, digest, contentLength, maxBytes, deadlineAt);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkDeadline(deadlineAt);
                appendSearchable(searchable, buffer, read);
                contentLength = write(buffer, read, output, digest, contentLength, maxBytes, deadlineAt);
            }
            if (contentLength == 0L) {
                throw new UnsupportedDocumentMediaTypeException();
            }
            output.flush();
            String searchableText = contentType.isTextLike()
                    ? sanitizeSearchable(searchable.toByteArray())
                    : DocumentTextExtractor.extract(stagingPath, contentType, ocrExtractor);
            return new StagedDocumentObject(
                    stagingId,
                    HexFormat.of().formatHex(digest.digest()),
                    contentLength,
                    contentType,
                    searchableText);
        } catch (DocumentUploadTooLargeException
                | DocumentUploadDeadlineExceededException
                | UnsupportedDocumentMediaTypeException exception) {
            deletePathQuietly(stagingPath);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deletePathQuietly(stagingPath);
            throw new DocumentObjectStorageException(exception);
        } catch (RuntimeException exception) {
            deletePathQuietly(stagingPath);
            throw exception;
        }
    }

    private static void appendSearchable(ByteArrayOutputStream target, byte[] bytes) {
        appendSearchable(target, bytes, bytes.length);
    }

    private static void appendSearchable(ByteArrayOutputStream target, byte[] bytes, int length) {
        if (target == null || target.size() >= MAX_SEARCHABLE_BYTES || length <= 0) {
            return;
        }
        int copyLength = Math.min(length, MAX_SEARCHABLE_BYTES - target.size());
        target.write(bytes, 0, copyLength);
    }

    private static String sanitizeSearchable(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .strip();
    }

    @Override
    public StoredDocumentObject promote(StagedDocumentObject staged, UUID documentId) {
        if (staged == null || documentId == null) {
            throw new DocumentObjectStorageException();
        }
        Path source = stagingPath(staged.stagingId());
        Path targetDirectory = objectDirectory(documentId);
        Path target = requireSafeChild(targetDirectory, staged.stagingId() + ".blob");
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new DocumentObjectStorageException();
            }
            Files.createDirectories(targetDirectory);
            moveAtomically(source, target);
            return new StoredDocumentObject("local:" + documentId + ":" + staged.stagingId());
        } catch (IOException exception) {
            throw new DocumentObjectStorageException(exception);
        }
    }

    @Override
    public void discard(StagedDocumentObject staged) {
        if (staged != null) {
            deletePath(stagingPath(staged.stagingId()));
        }
    }

    @Override
    public void delete(String objectReference) {
        ObjectReference parsed = ObjectReference.parse(objectReference);
        deletePath(objectPath(parsed));
        try {
            Files.deleteIfExists(objectDirectory(parsed.documentId()));
        } catch (IOException exception) {
            throw new DocumentObjectStorageException(exception);
        }
    }

    private long write(
            byte[] bytes,
            OutputStream output,
            MessageDigest digest,
            long currentLength,
            long maximumBytes,
            Instant deadlineAt)
            throws IOException {
        return write(bytes, bytes.length, output, digest, currentLength, maximumBytes, deadlineAt);
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
        checkDeadline(deadlineAt);
        long nextLength = currentLength + length;
        if (nextLength > maximumBytes) {
            throw new DocumentUploadTooLargeException();
        }
        output.write(bytes, 0, length);
        digest.update(bytes, 0, length);
        return nextLength;
    }

    private void checkDeadline(Instant deadlineAt) {
        if (clock.instant().isAfter(deadlineAt)) {
            throw new DocumentUploadDeadlineExceededException();
        }
    }

    private static void validateSignature(DocumentContentType contentType, byte[] prefix) {
        boolean valid = switch (contentType) {
            case PDF -> startsWith(prefix, new byte[] {'%', 'P', 'D', 'F', '-'});
            case PNG -> startsWith(prefix, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            case JPEG -> prefix.length >= 3
                    && prefix[0] == (byte) 0xff
                    && prefix[1] == (byte) 0xd8
                    && prefix[2] == (byte) 0xff;
            case DOCX, PPTX, XLSX -> isZipPrefix(prefix);
            case PLAIN_TEXT, CSV, MARKDOWN, HTML -> isSafeTextPrefix(prefix);
        };
        if (!valid) {
            throw new UnsupportedDocumentMediaTypeException();
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZipPrefix(byte[] prefix) {
        return startsWith(prefix, new byte[] {'P', 'K', 0x03, 0x04})
                || startsWith(prefix, new byte[] {'P', 'K', 0x05, 0x06});
    }

    private static boolean isSafeTextPrefix(byte[] prefix) {
        for (byte value : prefix) {
            if (value == 0) {
                return false;
            }
        }
        return true;
    }

    private static Path requireDirectory(Path path) {
        try {
            Files.createDirectories(path);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new DocumentObjectStorageException();
            }
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new DocumentObjectStorageException(exception);
        }
    }

    private Path stagingPath(UUID stagingId) {
        return requireSafeChild(stagingRoot, stagingId + ".part");
    }

    private Path objectDirectory(UUID documentId) {
        return requireSafeChild(objectRoot, documentId.toString());
    }

    private Path objectPath(ObjectReference reference) {
        return requireSafeChild(objectDirectory(reference.documentId()), reference.stagingId() + ".blob");
    }

    private static Path requireSafeChild(Path parent, String filename) {
        Path child = parent.resolve(filename).normalize();
        if (!child.startsWith(parent) || child.getParent() == null || !child.getParent().equals(parent)) {
            throw new DocumentObjectStorageException();
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

    private static void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new DocumentObjectStorageException(exception);
        }
    }

    private static void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original storage failure remains the only safe caller-facing detail.
        }
    }

    private record ObjectReference(UUID documentId, UUID stagingId) {

        private static ObjectReference parse(String value) {
            if (value == null || !value.matches("local:[0-9a-f-]{36}:[0-9a-f-]{36}")) {
                throw new DocumentObjectStorageException();
            }
            String[] components = value.split(":", -1);
            try {
                return new ObjectReference(UUID.fromString(components[1]), UUID.fromString(components[2]));
            } catch (IllegalArgumentException exception) {
                throw new DocumentObjectStorageException(exception);
            }
        }
    }
}

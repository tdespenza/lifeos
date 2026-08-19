package com.lifeos.profile.journal;

import com.mongodb.MongoWriteException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.UpdateResult;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bounded AES-GCM journal store. The owner UUID is the only lookup authority; plaintext journal
 * content is never sent to MongoDB and is never used as a query key.
 */
@Component
@ConditionalOnProperty(name = "profile.journal.enabled", havingValue = "true")
public class MongoJournalStore implements JournalStore {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String DELETE_FINGERPRINT = "DELETE";

    private final JournalProperties properties;
    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public MongoJournalStore(JournalProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.key = decodeKey(properties.getEncryptionKey());
        long timeoutMillis = properties.getTimeout().toMillis();
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(properties.getUri()))
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout((int) timeoutMillis, TimeUnit.MILLISECONDS)
                        .readTimeout((int) timeoutMillis, TimeUnit.MILLISECONDS))
                .build();
        this.client = MongoClients.create(settings);
        this.collection = client.getDatabase(properties.getDatabase()).getCollection(properties.getCollection());
        collection.createIndex(new Document("owner_account_id", 1).append("updated_at", -1));
        collection.createIndex(
                new Document("owner_account_id", 1).append("idempotency_key_hash", 1),
                new IndexOptions().unique(true).partialFilterExpression(Filters.exists("idempotency_key_hash")));
    }

    @Override
    public JournalMutationResult create(UUID ownerAccountId, String idempotencyKey, String title, String content) {
        requireOwner(ownerAccountId);
        String keyHash = digest(idempotencyKey);
        String fingerprint = fingerprint(title, content);
        UUID entryId = UUID.nameUUIDFromBytes(
                (ownerAccountId + ":CREATE:" + keyHash).getBytes(StandardCharsets.UTF_8));
        Document existing = collection.find(Filters.eq("_id", entryId.toString())).first();
        if (existing != null) {
            ensureMatching(existing, ownerAccountId, keyHash, fingerprint);
            return new JournalMutationResult(read(existing), true);
        }
        long count = collection.countDocuments(Filters.and(
                Filters.eq("owner_account_id", ownerAccountId.toString()), Filters.ne("deleted", true)));
        if (count >= properties.getMaxEntriesPerOwner()) {
            throw new JournalConflictException();
        }
        Instant now = Instant.now();
        Document document = baseDocument(entryId, ownerAccountId, title, content, now, now, 0L);
        document.append("idempotency_key_hash", keyHash).append("mutation_fingerprint", fingerprint);
        try {
            collection.insertOne(document);
            return new JournalMutationResult(read(document), false);
        } catch (MongoWriteException exception) {
            Document raced = collection.find(Filters.eq("_id", entryId.toString())).first();
            if (raced == null) {
                throw new JournalUnavailableException(exception);
            }
            ensureMatching(raced, ownerAccountId, keyHash, fingerprint);
            return new JournalMutationResult(read(raced), true);
        } catch (RuntimeException exception) {
            throw new JournalUnavailableException(exception);
        }
    }

    @Override
    public List<JournalEntry> list(UUID ownerAccountId, int requestedLimit) {
        requireOwner(ownerAccountId);
        int limit = Math.min(Math.max(1, requestedLimit), properties.getMaxPageSize());
        try {
            FindIterable<Document> documents = collection.find(Filters.and(
                            Filters.eq("owner_account_id", ownerAccountId.toString()),
                            Filters.ne("deleted", true)))
                    .sort(Sorts.descending("updated_at"))
                    .limit(limit);
            List<JournalEntry> entries = new ArrayList<>(limit);
            for (Document document : documents) {
                entries.add(read(document));
            }
            return List.copyOf(entries);
        } catch (RuntimeException exception) {
            throw new JournalUnavailableException(exception);
        }
    }

    @Override
    public JournalEntry get(UUID ownerAccountId, UUID entryId) {
        requireOwner(ownerAccountId);
        try {
            Document document = collection.find(Filters.and(
                            Filters.eq("_id", entryId.toString()),
                            Filters.eq("owner_account_id", ownerAccountId.toString()),
                            Filters.ne("deleted", true)))
                    .first();
            if (document == null) {
                throw new JournalNotFoundException();
            }
            return read(document);
        } catch (JournalNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JournalUnavailableException(exception);
        }
    }

    @Override
    public JournalMutationResult update(
            UUID ownerAccountId, UUID entryId, long expectedVersion, String idempotencyKey, String title, String content) {
        requireOwner(ownerAccountId);
        validateText(title, content);
        String keyHash = digest(idempotencyKey);
        String fingerprint = fingerprint(title, content);
        Document existing = findVisible(ownerAccountId, entryId);
        if (existing == null) {
            throw new JournalNotFoundException();
        }
        if (keyHash.equals(existing.getString("last_mutation_key_hash"))
                && fingerprint.equals(existing.getString("mutation_fingerprint"))) {
            return new JournalMutationResult(read(existing), true);
        }
        if (existing.getLong("version") != expectedVersion) {
            throw new JournalConflictException();
        }
        Instant now = Instant.now();
        SealedValue sealedTitle = seal(title);
        SealedValue sealedContent = seal(content);
        Document update = new Document("title_ciphertext", sealedTitle.ciphertext())
                .append("title_nonce", sealedTitle.nonce())
                .append("content_ciphertext", sealedContent.ciphertext())
                .append("content_nonce", sealedContent.nonce())
                .append("updated_at", Date.from(now))
                .append("version", expectedVersion + 1L)
                .append("last_mutation_key_hash", keyHash)
                .append("mutation_fingerprint", fingerprint);
        UpdateResult result = collection.updateOne(
                Filters.and(
                        Filters.eq("_id", entryId.toString()),
                        Filters.eq("owner_account_id", ownerAccountId.toString()),
                        Filters.eq("version", expectedVersion),
                        Filters.ne("deleted", true)),
                new Document("$set", update));
        if (result.getModifiedCount() != 1L) {
            Document raced = findVisible(ownerAccountId, entryId);
            if (raced != null && keyHash.equals(raced.getString("last_mutation_key_hash"))
                    && fingerprint.equals(raced.getString("mutation_fingerprint"))) {
                return new JournalMutationResult(read(raced), true);
            }
            throw raced == null ? new JournalNotFoundException() : new JournalConflictException();
        }
        return new JournalMutationResult(get(ownerAccountId, entryId), false);
    }

    @Override
    public boolean delete(UUID ownerAccountId, UUID entryId, long expectedVersion, String idempotencyKey) {
        requireOwner(ownerAccountId);
        String keyHash = digest(idempotencyKey);
        Document existing = findAny(ownerAccountId, entryId);
        if (existing == null) {
            throw new JournalNotFoundException();
        }
        if (keyHash.equals(existing.getString("last_mutation_key_hash"))
                && DELETE_FINGERPRINT.equals(existing.getString("mutation_fingerprint"))) {
            return true;
        }
        if (Boolean.TRUE.equals(existing.getBoolean("deleted"))) {
            throw new JournalNotFoundException();
        }
        if (existing.getLong("version") != expectedVersion) {
            throw new JournalConflictException();
        }
        UpdateResult result = collection.updateOne(
                Filters.and(
                        Filters.eq("_id", entryId.toString()),
                        Filters.eq("owner_account_id", ownerAccountId.toString()),
                        Filters.eq("version", expectedVersion),
                        Filters.ne("deleted", true)),
                new Document("$set", new Document("deleted", true)
                        .append("updated_at", Date.from(Instant.now()))
                        .append("version", expectedVersion + 1L)
                        .append("last_mutation_key_hash", keyHash)
                        .append("mutation_fingerprint", DELETE_FINGERPRINT)));
        if (result.getModifiedCount() != 1L) {
            throw new JournalConflictException();
        }
        return false;
    }

    @PreDestroy
    void close() {
        client.close();
    }

    private Document findVisible(UUID ownerAccountId, UUID entryId) {
        Document document = findAny(ownerAccountId, entryId);
        return document == null || Boolean.TRUE.equals(document.getBoolean("deleted")) ? null : document;
    }

    private Document findAny(UUID ownerAccountId, UUID entryId) {
        return collection.find(Filters.and(
                        Filters.eq("_id", entryId.toString()),
                        Filters.eq("owner_account_id", ownerAccountId.toString())))
                .first();
    }

    private Document baseDocument(
            UUID entryId, UUID ownerAccountId, String title, String content, Instant createdAt, Instant updatedAt, long version) {
        SealedValue sealedTitle = seal(title);
        SealedValue sealedContent = seal(content);
        return new Document("_id", entryId.toString())
                .append("owner_account_id", ownerAccountId.toString())
                .append("title_ciphertext", sealedTitle.ciphertext())
                .append("title_nonce", sealedTitle.nonce())
                .append("content_ciphertext", sealedContent.ciphertext())
                .append("content_nonce", sealedContent.nonce())
                .append("created_at", Date.from(createdAt))
                .append("updated_at", Date.from(updatedAt))
                .append("version", version)
                .append("deleted", false);
    }

    private JournalEntry read(Document document) {
        try {
            return new JournalEntry(
                    UUID.fromString(document.getString("_id")),
                    UUID.fromString(document.getString("owner_account_id")),
                    open(document.getString("title_nonce"), document.getString("title_ciphertext")),
                    open(document.getString("content_nonce"), document.getString("content_ciphertext")),
                    document.getDate("created_at").toInstant(),
                    document.getDate("updated_at").toInstant(),
                    document.getLong("version"));
        } catch (RuntimeException exception) {
            throw new JournalUnavailableException(exception);
        }
    }

    private void ensureMatching(Document document, UUID ownerAccountId, String keyHash, String fingerprint) {
        if (!ownerAccountId.toString().equals(document.getString("owner_account_id"))
                || !keyHash.equals(document.getString("idempotency_key_hash"))
                || !fingerprint.equals(document.getString("mutation_fingerprint"))) {
            throw new JournalConflictException();
        }
    }

    private void validateText(String title, String content) {
        if (title == null || title.isBlank() || title.length() > 200 || content == null || content.isBlank()
                || content.getBytes(StandardCharsets.UTF_8).length > properties.getMaxContentBytes()) {
            throw new IllegalArgumentException("Journal title and content are invalid");
        }
    }

    private void requireOwner(UUID ownerAccountId) {
        if (ownerAccountId == null) {
            throw new IllegalArgumentException("ownerAccountId must not be null");
        }
    }

    private String fingerprint(String title, String content) {
        validateText(title, content);
        return digest(title + "\u0000" + content);
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private SealedValue seal(String value) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new SealedValue(
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception exception) {
            throw new JournalUnavailableException(exception);
        }
    }

    private String open(String nonce, String ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonce)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new JournalUnavailableException(exception);
        }
    }

    private static SecretKey decodeKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) {
                throw new IllegalArgumentException("Journal encryption key must be AES-256");
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Journal encryption key is invalid", exception);
        }
    }

    private record SealedValue(String nonce, String ciphertext) {
    }
}

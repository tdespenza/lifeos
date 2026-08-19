package com.lifeos.assistant.history;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** AES-GCM encrypted MongoDB history with bounded per-conversation reads and TTL cleanup. */
@Component
@ConditionalOnProperty(value = "ai-assistant.conversation-history.enabled", havingValue = "true")
public class EncryptedMongoConversationHistoryStore implements AssistantConversationHistoryStore,
        AutoCloseable {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final SecretKeySpec key;
    private final ConversationHistoryProperties properties;
    private final SecureRandom random = new SecureRandom();

    public EncryptedMongoConversationHistoryStore(ConversationHistoryProperties properties) {
        this.properties = properties;
        key = new SecretKeySpec(Base64.getDecoder().decode(properties.getEncryptionKey()), "AES");
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(properties.getUri()))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(properties.getConnectTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(properties.getConnectTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                .build();
        client = MongoClients.create(settings);
        MongoDatabase database = client.getDatabase(properties.getDatabase());
        collection = database.getCollection(properties.getCollection());
        collection.createIndex(Indexes.ascending("ownerAccountId", "conversationId", "createdAt"));
        collection.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Override
    public void append(UUID ownerAccountId, UUID conversationId, String role, String content) {
        if (ownerAccountId == null || conversationId == null || role == null || content == null
                || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > properties.getMaxEntryBytes()) {
            throw new ConversationHistoryUnavailableException(new IllegalArgumentException("history entry is invalid or too large"));
        }
        try {
            Instant now = Instant.now();
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(content.getBytes(StandardCharsets.UTF_8));
            collection.insertOne(new Document("ownerAccountId", ownerAccountId.toString())
                    .append("conversationId", conversationId.toString())
                    .append("role", role)
                    .append("nonce", Base64.getEncoder().encodeToString(nonce))
                    .append("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
                    .append("createdAt", Date.from(now))
                    .append("expiresAt", Date.from(now.plus(properties.getRetentionDays(), ChronoUnit.DAYS))));
            prune(ownerAccountId, conversationId);
        } catch (Exception exception) {
            throw new ConversationHistoryUnavailableException(exception);
        }
    }

    @Override
    public List<HistoryEntry> read(UUID ownerAccountId, UUID conversationId) {
        if (ownerAccountId == null || conversationId == null) {
            throw new ConversationHistoryUnavailableException(new IllegalArgumentException("history scope is invalid"));
        }
        try {
            List<HistoryEntry> result = new ArrayList<>();
            for (Document document : collection.find(Filters.and(
                            Filters.eq("ownerAccountId", ownerAccountId.toString()),
                            Filters.eq("conversationId", conversationId.toString())))
                    .sort(Sorts.ascending("createdAt"))
                    .limit(properties.getMaxEntriesPerConversation())) {
                byte[] nonce = Base64.getDecoder().decode(document.getString("nonce"));
                byte[] ciphertext = Base64.getDecoder().decode(document.getString("ciphertext"));
                String content = new String(cipher(Cipher.DECRYPT_MODE, nonce).doFinal(ciphertext), StandardCharsets.UTF_8);
                result.add(new HistoryEntry(document.getString("role"), content,
                        document.getDate("createdAt").toInstant()));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new ConversationHistoryUnavailableException(exception);
        }
    }

    private void prune(UUID ownerAccountId, UUID conversationId) {
        List<Document> entries = collection.find(Filters.and(
                        Filters.eq("ownerAccountId", ownerAccountId.toString()),
                        Filters.eq("conversationId", conversationId.toString())))
                .sort(Sorts.descending("createdAt"))
                .skip(properties.getMaxEntriesPerConversation())
                .into(new ArrayList<>());
        for (Document entry : entries) {
            collection.deleteOne(Filters.eq("_id", entry.getObjectId("_id")));
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }

    @Override
    public void close() {
        client.close();
    }
}

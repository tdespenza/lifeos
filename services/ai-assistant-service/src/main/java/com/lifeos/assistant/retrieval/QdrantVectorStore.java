package com.lifeos.assistant.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Bounded Qdrant REST adapter with owner filtering and no unbounded fallback scan. */
@Component
public class QdrantVectorStore {

    private final RestClient restClient;
    private final QdrantProperties properties;
    private final Semaphore permits = new Semaphore(32, true);

    @Autowired
    public QdrantVectorStore(RestClient.Builder builder, QdrantProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.getTimeout());
        restClient = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
        this.properties = properties;
    }

    QdrantVectorStore(RestClient restClient, QdrantProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public SearchResult search(UUID ownerAccountId, String query, int requestedLimit) {
        if (!properties.isEnabled()) {
            return SearchResult.unavailable();
        }
        if (ownerAccountId == null || query == null || query.isBlank()) {
            return SearchResult.empty();
        }
        int limit = Math.min(Math.max(1, requestedLimit), properties.getMaxResults());
        if (!permits.tryAcquire()) {
            return SearchResult.unavailable();
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/collections/{collection}/points/search", properties.getCollection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> addApiKey(headers))
                    .body(Map.of(
                            "vector", DocumentEmbedding.embed(query, properties.getVectorSize()),
                            "limit", limit,
                            "with_payload", true,
                            "filter", Map.of("must", List.of(Map.of(
                                    "key", "owner_account_id",
                                    "match", Map.of("value", ownerAccountId.toString()))))))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(response);
        } catch (RuntimeException exception) {
            return SearchResult.unavailable();
        } finally {
            permits.release();
        }
    }

    public SearchResult fetchDocument(UUID ownerAccountId, UUID documentId) {
        if (!properties.isEnabled() || ownerAccountId == null || documentId == null) {
            return SearchResult.unavailable();
        }
        if (!permits.tryAcquire()) {
            return SearchResult.unavailable();
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/collections/{collection}/points/scroll", properties.getCollection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> addApiKey(headers))
                    .body(Map.of(
                            "limit", properties.getMaxChunksPerDocument(),
                            "with_payload", true,
                            "filter", Map.of("must", List.of(
                                    Map.of("key", "owner_account_id", "match", Map.of("value", ownerAccountId.toString())),
                                    Map.of("key", "document_id", "match", Map.of("value", documentId.toString()))))))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(response);
        } catch (RuntimeException exception) {
            return SearchResult.unavailable();
        } finally {
            permits.release();
        }
    }

    private SearchResult parse(JsonNode response) {
        if (response == null || !response.has("result")) {
            return SearchResult.unavailable();
        }
        JsonNode points = response.get("result");
        if (points.isObject()) {
            points = points.get("points");
        }
        if (points == null || !points.isArray()) {
            return SearchResult.unavailable();
        }
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (JsonNode point : points) {
            JsonNode payload = point.path("payload");
            UUID documentId = uuid(payload.path("document_id").asText(null));
            UUID chunkId = uuid(payload.path("chunk_id").asText(null));
            String snippet = payload.path("snippet").asText("");
            if (documentId == null || chunkId == null || !StringUtils.hasText(snippet)
                    || snippet.length() > properties.getMaxChunkCharacters()) {
                continue;
            }
            chunks.add(new RetrievedChunk(
                    documentId,
                    chunkId,
                    snippet,
                    point.path("score").asDouble(0.0d),
                    payload.path("document_version").asLong(0L)));
        }
        return SearchResult.available(List.copyOf(chunks));
    }

    private void addApiKey(org.springframework.http.HttpHeaders headers) {
        if (StringUtils.hasText(properties.getApiKey())) {
            headers.set("api-key", properties.getApiKey());
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record RetrievedChunk(UUID documentId, UUID chunkId, String snippet, double score, long documentVersion) {

        public RetrievedChunk(UUID documentId, UUID chunkId, String snippet, double score) {
            this(documentId, chunkId, snippet, score, 0L);
        }
    }

    public record SearchResult(boolean available, List<RetrievedChunk> chunks) {

        static SearchResult unavailable() {
            return new SearchResult(false, List.of());
        }

        static SearchResult empty() {
            return new SearchResult(true, List.of());
        }

        static SearchResult available(List<RetrievedChunk> chunks) {
            return new SearchResult(true, chunks);
        }
    }
}

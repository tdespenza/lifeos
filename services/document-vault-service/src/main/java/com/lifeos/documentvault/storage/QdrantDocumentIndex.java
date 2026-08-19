package com.lifeos.documentvault.storage;

import com.lifeos.documentvault.config.QdrantDocumentProperties;
import com.lifeos.documentvault.domain.VaultDocument;
import com.lifeos.documentvault.service.DocumentVaultMetrics;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Optional, best-effort vector projection; the Postgres document row remains authoritative. */
@Component
public class QdrantDocumentIndex {

    private final RestClient restClient;
    private final QdrantDocumentProperties properties;
    private final DocumentVaultMetrics metrics;
    private final AtomicBoolean collectionReady = new AtomicBoolean();

    @Autowired
    public QdrantDocumentIndex(
            RestClient.Builder builder, QdrantDocumentProperties properties, DocumentVaultMetrics metrics) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getTimeout());
        restClient = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
        this.properties = properties;
        this.metrics = metrics;
    }

    QdrantDocumentIndex(RestClient restClient, QdrantDocumentProperties properties, DocumentVaultMetrics metrics) {
        this.restClient = restClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void index(VaultDocument document, String searchableText) {
        if (!properties.isEnabled() || document == null || !StringUtils.hasText(searchableText)) {
            return;
        }
        try {
            ensureCollection();
            List<Map<String, Object>> points = points(document, searchableText);
            if (!points.isEmpty()) {
                restClient.put()
                        .uri(uriBuilder -> uriBuilder.path("/collections/{collection}/points")
                                .queryParam("wait", "true")
                                .build(properties.getCollection()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(this::addApiKey)
                        .body(Map.of("points", points))
                        .retrieve()
                        .toBodilessEntity();
            }
            metrics.record("vector_index", "success");
        } catch (RuntimeException exception) {
            collectionReady.set(false);
            // Vector indexing is eventually consistent and must not make an already committed
            // document unavailable. Retrieval reports dependency degradation separately.
            metrics.record("vector_index", "unavailable");
        }
    }

    private void ensureCollection() {
        if (collectionReady.get()) {
            return;
        }
        synchronized (collectionReady) {
            if (collectionReady.get()) {
                return;
            }
            try {
                restClient.put()
                        .uri("/collections/{collection}", properties.getCollection())
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(this::addApiKey)
                        .body(Map.of(
                                "vectors", Map.of("size", properties.getVectorSize(), "distance", "Cosine")))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() != 409) {
                    throw exception;
                }
            }
            collectionReady.set(true);
        }
    }

    private List<Map<String, Object>> points(VaultDocument document, String searchableText) {
        int chunkSize = properties.getMaxChunkCharacters();
        int maxChunks = properties.getMaxChunksPerDocument();
        List<Map<String, Object>> points = new ArrayList<>();
        for (int offset = 0, chunkNumber = 0;
                offset < searchableText.length() && chunkNumber < maxChunks;
                offset += chunkSize, chunkNumber++) {
            String snippet = searchableText.substring(offset, Math.min(searchableText.length(), offset + chunkSize)).strip();
            if (snippet.isBlank()) {
                continue;
            }
            UUID chunkId = UUID.nameUUIDFromBytes(
                    (document.getId() + ":" + document.getVersion() + ":" + chunkNumber)
                            .getBytes(StandardCharsets.UTF_8));
            points.add(Map.of(
                    "id", chunkId,
                    "vector", embed(snippet),
                    "payload", Map.of(
                            "document_id", document.getId(),
                            "chunk_id", chunkId,
                            "owner_account_id", document.getOwnerAccountId(),
                            "tenant_id", document.getTenantId(),
                            "document_version", document.getVersion(),
                            "snippet", snippet)));
        }
        return List.copyOf(points);
    }

    private float[] embed(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[properties.getVectorSize()];
            for (int index = 0; index < vector.length; index++) {
                int first = bytes[index % bytes.length] & 0xff;
                int second = bytes[(index * 7 + 3) % bytes.length] & 0xff;
                vector[index] = ((first / 255.0f) * 2.0f) - 1.0f + ((second / 255.0f) - 0.5f) * 0.05f;
            }
            return vector;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private void addApiKey(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getApiKey())) {
            headers.set("api-key", properties.getApiKey());
        }
    }
}

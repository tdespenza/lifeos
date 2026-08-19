package com.lifeos.documentvault.storage;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.lifeos.documentvault.config.QdrantDocumentProperties;
import com.lifeos.documentvault.domain.DocumentClassification;
import com.lifeos.documentvault.domain.DocumentMetadata;
import com.lifeos.documentvault.domain.DocumentSource;
import com.lifeos.documentvault.domain.VaultDocument;
import com.lifeos.documentvault.service.DocumentVaultMetrics;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QdrantDocumentIndexTest {

    @Test
    void disabledIndexDoesNotCallVectorInfrastructure() {
        QdrantDocumentProperties properties = new QdrantDocumentProperties();
        QdrantDocumentIndex index = new QdrantDocumentIndex(
                RestClient.builder().build(), properties, mock(DocumentVaultMetrics.class));

        index.index(document(), "private evidence");
    }

    @Test
    void createsCollectionAndUpsertsOwnerScopedBoundedChunks() {
        QdrantDocumentProperties properties = new QdrantDocumentProperties();
        properties.setEnabled(true);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://qdrant.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantDocumentIndex index = new QdrantDocumentIndex(builder.build(), properties, mock(DocumentVaultMetrics.class));
        server.expect(requestTo("https://qdrant.test/collections/lifeos_documents_v1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.vectors.size").value(32))
                .andExpect(jsonPath("$.vectors.distance").value("Cosine"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://qdrant.test/collections/lifeos_documents_v1/points?wait=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.points[0].payload.document_id").value(document().getId().toString()))
                .andExpect(jsonPath("$.points[0].payload.owner_account_id").value(document().getOwnerAccountId().toString()))
                .andExpect(jsonPath("$.points[0].payload.tenant_id").value(document().getTenantId()))
                .andExpect(jsonPath("$.points[0].payload.snippet").value("private evidence"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        index.index(document(), "private evidence");

        server.verify();
    }

    private static VaultDocument document() {
        UUID owner = UUID.fromString("11111111-1111-1111-8111-111111111111");
        return new VaultDocument(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                owner,
                "personal:" + owner,
                "local:22222222-2222-4222-8222-222222222222:33333333-3333-4333-8333-333333333333",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                16,
                "text/plain",
                new DocumentMetadata("Evidence", java.util.List.of(), Instant.now(), DocumentSource.UPLOAD,
                        DocumentClassification.PRIVATE),
                Instant.now());
    }
}

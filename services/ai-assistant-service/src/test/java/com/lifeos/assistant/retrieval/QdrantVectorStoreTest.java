package com.lifeos.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QdrantVectorStoreTest {

    @Test
    void disabledStoreFailsClosedWithoutCallingAProvider() {
        QdrantProperties properties = new QdrantProperties();
        QdrantVectorStore store = new QdrantVectorStore(RestClient.builder().build(), properties);

        QdrantVectorStore.SearchResult result = store.search(UUID.randomUUID(), "private question", 8);

        assertThat(result.available()).isFalse();
        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void searchFiltersByOwnerAndReturnsOnlyBoundedValidPayloads() {
        QdrantProperties properties = new QdrantProperties();
        properties.setEnabled(true);
        UUID owner = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://qdrant.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantVectorStore store = new QdrantVectorStore(builder.build(), properties);
        server.expect(requestTo("https://qdrant.test/collections/lifeos_documents_v1/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.limit").value(8))
                .andExpect(jsonPath("$.filter.must[0].key").value("owner_account_id"))
                .andExpect(jsonPath("$.filter.must[0].match.value").value(owner.toString()))
                .andRespond(withSuccess(
                        "{\"result\":["
                                + "{\"id\":\"bad\",\"score\":0.9,\"payload\":{\"snippet\":\"ignored\"}},"
                                + "{\"id\":\"good\",\"score\":0.8,\"payload\":{"
                                + "\"document_id\":\"11111111-1111-1111-8111-111111111111\","
                                + "\"chunk_id\":\"22222222-2222-4222-8222-222222222222\","
                                + "\"snippet\":\"bounded evidence\"}}]}" ,
                        MediaType.APPLICATION_JSON));

        QdrantVectorStore.SearchResult result = store.search(owner, "question", 8);

        assertThat(result.available()).isTrue();
        assertThat(result.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.documentId()).isEqualTo(UUID.fromString("11111111-1111-1111-8111-111111111111"));
            assertThat(chunk.chunkId()).isEqualTo(UUID.fromString("22222222-2222-4222-8222-222222222222"));
            assertThat(chunk.snippet()).isEqualTo("bounded evidence");
        });
        server.verify();
    }
}

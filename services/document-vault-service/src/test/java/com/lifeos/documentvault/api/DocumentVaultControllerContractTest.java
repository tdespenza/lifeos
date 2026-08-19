package com.lifeos.documentvault.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.documentvault.audit.DocumentVaultSecurityAuditEventRepository;
import com.lifeos.documentvault.authorization.DocumentVaultAccessService;
import com.lifeos.documentvault.authorization.DocumentVaultSubject;
import com.lifeos.documentvault.domain.VaultDocumentRepository;
import com.lifeos.documentvault.idempotency.DocumentCommandIdempotencyRepository;
import com.lifeos.documentvault.proof.DocumentProofOutboxEventRepository;
import com.lifeos.documentvault.proof.DocumentProofRequestRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Executable public HTTP contract for multipart admission, exact replay, ETags, and no disclosure. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:document-vault-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=document-vault-contract-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "document-vault.idempotency-secret=contract-idempotency-secret",
    "document-vault.audit-client-fingerprint-secret=contract-audit-secret",
    "document-vault.proof-outbox.relay-enabled=false",
    "identity.workload-token=contract-workload-token"
})
@AutoConfigureMockMvc
class DocumentVaultControllerContractTest {

    private static final String BEARER = "Bearer document-vault-contract-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "lifeos-document-vault-contract-" + UUID.randomUUID());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VaultDocumentRepository documentRepository;

    @Autowired
    private DocumentCommandIdempotencyRepository idempotencyRepository;

    @Autowired
    private DocumentVaultSecurityAuditEventRepository auditRepository;

    @Autowired
    private DocumentProofRequestRepository proofRequestRepository;

    @Autowired
    private DocumentProofOutboxEventRepository proofOutboxRepository;

    @MockitoBean
    private DocumentVaultAccessService accessService;

    private DocumentVaultSubject subject;

    @DynamicPropertySource
    static void localStorage(DynamicPropertyRegistry registry) {
        registry.add("document-vault.storage.local-root", () -> STORAGE_ROOT.toString());
    }

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        proofOutboxRepository.deleteAll();
        proofRequestRepository.deleteAll();
        idempotencyRepository.deleteAll();
        documentRepository.deleteAll();
        subject = subject();
        when(accessService.authenticate(anyString())).thenAnswer(invocation -> subject);
    }

    @Test
    void matchingMultipartRetryReturnsOriginalSnapshotAfterLaterMetadataUpdate() throws Exception {
        MvcResult first = upload("receipt-upload-key", "Travel receipt", "UPLOAD")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.matchesPattern("/api/v1/documents/.+")))
                .andExpect(jsonPath("$.objectReference").doesNotExist())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        UUID documentId = UUID.fromString(firstBody.path("id").asText());

        mockMvc.perform(put("/api/v1/documents/{documentId}/metadata", documentId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "receipt-metadata-update")
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metadataJson("Travel receipt categorized", "IMPORT")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        MvcResult replay = upload("receipt-upload-key", "Travel receipt", "UPLOAD")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();

        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())).isEqualTo(firstBody);
        assertThat(documentRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void returnsTheSameGenericNotFoundBodyForMissingAndCrossOwnerDocument() throws Exception {
        UUID documentId = UUID.fromString(objectMapper.readTree(upload("private-upload", "Private receipt", "UPLOAD")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());
        subject = subject();

        MvcResult crossOwner = mockMvc.perform(get("/api/v1/documents/{documentId}", documentId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/documents/{documentId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(crossOwner.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .doesNotContain(documentId.toString());
    }

    @Test
    void rejectsMissingStrictHeadersAndUnsupportedMediaBeforePersistence() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(pdfFile())
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .param("title", "No key"))
                .andExpect(status().isBadRequest());

        UUID documentId = UUID.fromString(objectMapper.readTree(upload("header-document", "Header document", "UPLOAD")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());
        mockMvc.perform(put("/api/v1/documents/{documentId}/metadata", documentId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "missing-if-match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metadataJson("Header document", "UPLOAD")))
                .andExpect(status().is(428));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile(
                                "file", "unsafe.bin", "application/x-msdownload", "MZ".getBytes(StandardCharsets.US_ASCII)))
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "unsupported-type")
                        .param("title", "Unsafe"))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(documentRepository.count()).isEqualTo(1L);
    }

    @Test
    void validatesBoundedSearchAndReturnsSourceAndRelevanceWithoutAStorageReference() throws Exception {
        upload("travel-search", "Travel itinerary", "UPLOAD").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/documents/search")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .param("q", "travel")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].source").value("UPLOAD"))
                .andExpect(jsonPath("$.results[0].relevance").isNumber())
                .andExpect(jsonPath("$.results[0].objectReference").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get("/api/v1/documents/search")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .param("q", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsAndReplaysDurableProofRequestWithoutExposingStorageFacts() throws Exception {
        JsonNode document = objectMapper.readTree(upload("proof-upload", "Proof document", "UPLOAD")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        UUID documentId = UUID.fromString(document.path("id").asText());

        MvcResult first = mockMvc.perform(post("/api/v1/documents/{documentId}/proof-requests", documentId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "proof-request"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        org.hamcrest.Matchers.matchesPattern("/api/v1/documents/.+/proof-requests/.+")))
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.checksumSha256").isString())
                .andExpect(jsonPath("$.objectReference").doesNotExist())
                .andReturn();

        mockMvc.perform(post("/api/v1/documents/{documentId}/proof-requests", documentId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "proof-request"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.requestId").value(
                        objectMapper.readTree(first.getResponse().getContentAsString()).path("requestId").asText()));

        assertThat(proofRequestRepository.count()).isEqualTo(1L);
        assertThat(proofOutboxRepository.count()).isEqualTo(1L);
    }

    private org.springframework.test.web.servlet.ResultActions upload(String key, String title, String source)
            throws Exception {
        return mockMvc.perform(multipart("/api/v1/documents")
                .file(pdfFile())
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .header("Idempotency-Key", key)
                .param("title", title)
                .param("tag", "travel")
                .param("source", source)
                .param("classification", "PRIVATE"));
    }

    private static MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file",
                "client-controlled-name.pdf",
                "application/pdf",
                "%PDF-1.7\ncontract document".getBytes(StandardCharsets.US_ASCII));
    }

    private static String metadataJson(String title, String source) {
        return "{\"title\":\"" + title
                + "\",\"tags\":[\"travel\"],\"documentTimestamp\":\"2026-08-18T12:00:00Z\",\"source\":\""
                + source
                + "\",\"classification\":\"PRIVATE\"}";
    }

    private static DocumentVaultSubject subject() {
        return new DocumentVaultSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }
}

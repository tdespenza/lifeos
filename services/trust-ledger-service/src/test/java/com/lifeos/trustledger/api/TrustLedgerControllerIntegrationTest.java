package com.lifeos.trustledger.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.trustledger.access.TrustAccessService;
import com.lifeos.trustledger.access.TrustSubject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Runs real proof primitives through the Spring HTTP boundary without contacting Identity. */
@SpringBootTest(properties = "identity.workload-token=trust-ledger-integration-workload-token")
@AutoConfigureMockMvc
class TrustLedgerControllerIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String FIRST_DIGEST =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SECOND_DIGEST =
            "2222222222222222222222222222222222222222222222222222222222222222";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrustAccessService accessService;

    @BeforeEach
    void setUp() {
        when(accessService.authenticate(any())).thenReturn(new TrustSubject(
                UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF));
    }

    @Test
    void realDocumentHashAndMerkleVerificationRoundTripWithoutPersistingContent() throws Exception {
        mockMvc.perform(multipart("/api/v1/trust/document-proofs")
                        .file(new MockMultipartFile("content", "journal.txt", "text/plain", "private words".getBytes()))
                        .param("mediaType", "text/plain")
                        .param("proofPurpose", "integrity")
                        .header("Authorization", "Bearer integration-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("SHA-256"))
                .andExpect(jsonPath("$.contentBytes").value(13));

        String response = mockMvc.perform(post("/api/v1/trust/merkle-proofs")
                        .header("Authorization", "Bearer integration-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentDigests\":[\"" + FIRST_DIGEST + "\",\"" + SECOND_DIGEST + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proofs.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        com.fasterxml.jackson.databind.JsonNode proof = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response)
                .path("proofs")
                .get(0);
        String root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("root").asText();
        mockMvc.perform(post("/api/v1/trust/merkle-proofs/verify")
                        .header("Authorization", "Bearer integration-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"root\":\"" + root + "\",\"leafIndex\":" + proof.path("leafIndex").asInt()
                                + ",\"documentDigest\":\"" + proof.path("documentDigest").asText()
                                + "\",\"steps\":" + proof.path("steps") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }
}

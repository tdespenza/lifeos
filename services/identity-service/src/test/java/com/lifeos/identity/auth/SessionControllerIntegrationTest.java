package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end acceptance coverage for ownership, durable revocation, and refresh invalidation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository accountRepository;

    @Autowired
    private PasswordCredentialRepository credentialRepository;

    @Autowired
    private AuthSessionRepository sessionRepository;

    @Autowired
    private TokenFamilyRepository familyRepository;

    @Autowired
    private ConsumedRefreshTokenRepository consumedRepository;

    @Autowired
    private RefreshReplayRecordRepository replayRepository;

    @Autowired
    private SecurityAuditEventRepository auditRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @MockitoBean
    private LoginRateLimiter rateLimiter;

    @MockitoBean
    private SessionRevocationCache revocationCache;

    @BeforeEach
    void cleanDatabase() {
        reset(rateLimiter, revocationCache);
        replayRepository.deleteAll();
        consumedRepository.deleteAll();
        familyRepository.deleteAll();
        auditRepository.deleteAll();
        sessionRepository.deleteAll();
        credentialRepository.deleteAll();
        accountRepository.deleteAll();
        when(revocationCache.isRevoked(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void revokingOneSessionBlocksAccessAndRefreshWhileOtherSessionRemainsValid() throws Exception {
        UserAccount account = accountRepository.saveAndFlush(
                new UserAccount("session-" + UUID.randomUUID() + "@example.com", "Ada Lovelace"));
        credentialRepository.saveAndFlush(new PasswordCredential(
                account, passwordEncoder.encode("correct horse battery staple")));

        JsonNode first = login("first-token");
        JsonNode second = login("second-token");

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + first.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(2))
                .andExpect(jsonPath("$.sessions[0].deviceLabel").value("chrome on macos"));

        mockMvc.perform(post("/api/v1/auth/sessions/{id}/revoke", second.get("sessionId").asText())
                        .header("Authorization", "Bearer " + first.get("accessToken").asText()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + second.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Idempotency-Key", "revoke-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("refreshToken", second.get("refreshToken").asText())
                                .toString()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + first.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[1].revoked").value(true));

        assertThat(sessionRepository.countActiveByAccountId(account.getId(), java.time.Instant.now()))
                .isEqualTo(1);
    }

    private JsonNode login(String marker) throws Exception {
        String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36";
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .header("User-Agent", userAgent + " " + marker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + findAccountEmail() + "\","
                                + "\"password\":\"correct horse battery staple\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String findAccountEmail() {
        return accountRepository.findAll().stream().findFirst().orElseThrow().getEmail();
    }
}

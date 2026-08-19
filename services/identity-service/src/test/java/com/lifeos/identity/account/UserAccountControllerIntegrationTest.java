package com.lifeos.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.identity.auth.AuthSessionRepository;
import com.lifeos.identity.auth.ConsumedRefreshTokenRepository;
import com.lifeos.identity.auth.ExternalIdentityRepository;
import com.lifeos.identity.auth.LoginRateLimiter;
import com.lifeos.identity.auth.PasswordCredential;
import com.lifeos.identity.auth.PasswordCredentialRepository;
import com.lifeos.identity.auth.RefreshReplayRecordRepository;
import com.lifeos.identity.auth.RegistrationIdempotencyFingerprint;
import com.lifeos.identity.auth.SecurityAuditEvent;
import com.lifeos.identity.auth.SecurityAuditEventRepository;
import com.lifeos.identity.auth.SecurityAuditEventType;
import com.lifeos.identity.auth.TokenFamilyRepository;
import com.lifeos.identity.auth.WebAuthnCredentialRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP contracts for first-party account enrollment and bearer-owned account reads. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAccountControllerIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository accountRepository;

    @Autowired
    private AccountRegistrationIdempotencyRepository registrationIdempotencyRepository;

    @Autowired
    private PasswordCredentialRepository credentialRepository;

    @Autowired
    private AuthSessionRepository sessionRepository;

    @Autowired
    private TokenFamilyRepository familyRepository;

    @Autowired
    private ConsumedRefreshTokenRepository consumedTokenRepository;

    @Autowired
    private RefreshReplayRecordRepository refreshReplayRepository;

    @Autowired
    private SecurityAuditEventRepository auditRepository;

    @Autowired
    private ExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private WebAuthnCredentialRepository webAuthnCredentialRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RegistrationIdempotencyFingerprint registrationFingerprint;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private LoginRateLimiter rateLimiter;

    @LocalManagementPort
    private int managementPort;

    @BeforeEach
    void cleanDatabase() {
        reset(rateLimiter);
        refreshReplayRepository.deleteAll();
        consumedTokenRepository.deleteAll();
        familyRepository.deleteAll();
        sessionRepository.deleteAll();
        webAuthnCredentialRepository.deleteAll();
        externalIdentityRepository.deleteAll();
        auditRepository.deleteAll();
        registrationIdempotencyRepository.deleteAll();
        credentialRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void registrationCreatesArgon2CredentialAndAllowsPasswordLogin() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "register-ada-001")
                        .header("X-Correlation-ID", "registration-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest("ada@example.com", "Ada Lovelace", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/accounts/")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
                .andReturn();
        UUID accountId = accountId(registration);

        PasswordCredential credential = credentialRepository.findByAccountId(accountId).orElseThrow();
        assertThat(credential.getEncodedPassword()).startsWith("$argon2id$");
        assertThat(passwordEncoder.matches(PASSWORD, credential.getEncodedPassword())).isTrue();
        assertThat(auditEvents(SecurityAuditEventType.ACCOUNT_REGISTRATION_SUCCEEDED))
                .singleElement()
                .extracting(SecurityAuditEvent::getAccountId)
                .isEqualTo(accountId);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequest("ada@example.com", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value(matchesPattern("[^.]+\\.[^.]+\\.[^.]+")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));

        assertThat(sessionRepository.countActiveByAccountId(accountId, java.time.Instant.now())).isEqualTo(1);
    }

    @Test
    void matchingIdempotencyRetryReturnsTheOriginalAccountWithoutAnotherCredential() throws Exception {
        String request = registrationRequest("ada@example.com", "Ada Lovelace", PASSWORD);

        MvcResult created = mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "register-ada-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replayed = mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "register-ada-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", containsString("/api/v1/accounts/")))
                .andReturn();

        assertThat(accountId(replayed)).isEqualTo(accountId(created));
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(credentialRepository.count()).isEqualTo(1);
        assertThat(registrationIdempotencyRepository.count()).isEqualTo(1);
        assertThat(auditEvents(SecurityAuditEventType.ACCOUNT_REGISTRATION_SUCCEEDED)).hasSize(1);
        assertThat(auditEvents(SecurityAuditEventType.ACCOUNT_REGISTRATION_REPLAYED)).hasSize(1);
    }

    @Test
    void matchingRetryRecoversACommittedPendingReservation() throws Exception {
        String email = "recovery@example.com";
        String displayName = "Recovery Account";
        String key = "register-pending-recovery";
        registrationIdempotencyRepository.saveAndFlush(new AccountRegistrationIdempotency(
                registrationFingerprint.keyHash(key),
                registrationFingerprint.requestFingerprint(
                        EmailAddressNormalizer.normalize(email), displayName, PASSWORD)));

        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest(email, displayName, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(credentialRepository.findByAccountId(accountId(result))).isPresent();
        AccountRegistrationIdempotency recovered = registrationIdempotencyRepository
                .findByIdempotencyKeyHash(registrationFingerprint.keyHash(key))
                .orElseThrow();
        assertThat(recovered.isCompleted()).isTrue();
        assertThat(recovered.matchesRequestFingerprint(registrationFingerprint.requestFingerprint(
                        EmailAddressNormalizer.normalize(email), displayName, PASSWORD)))
                .isTrue();
    }

    @Test
    void registrationRejectsInvalidPasswordAndIdempotencyInputWithoutEchoingSecrets() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "register-invalid-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest("ada@example.com", "Ada Lovelace", "too-short")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("too-short"))))
                .andExpect(content().string(not(containsString("ada@example.com"))));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest("ada@example.com", "Ada Lovelace", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(PASSWORD))));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "one-key", "another-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest("ada@example.com", "Ada Lovelace", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("one-key"))))
                .andExpect(content().string(not(containsString(PASSWORD))));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "register-missing-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.com\",\"displayName\":\"Ada Lovelace\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("ada@example.com"))));

        assertThat(accountRepository.count()).isZero();
        assertThat(credentialRepository.count()).isZero();
        assertThat(auditEvents(SecurityAuditEventType.ACCOUNT_REGISTRATION_REJECTED)).hasSize(4);
    }

    @Test
    void registrationConflictsDoNotExposeEmailOrPermitPayloadSubstitution() throws Exception {
        registerAccount("ada@example.com", "Ada Lovelace", PASSWORD, "register-conflict-key");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "register-conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest("ada@example.com", "Different Name", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("ada@example.com"))))
                .andExpect(content().string(not(containsString("Different Name"))));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", "register-other-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest("ada@example.com", "Ada Lovelace", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("ada@example.com"))));

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(credentialRepository.count()).isEqualTo(1);
        assertThat(auditEvents(SecurityAuditEventType.ACCOUNT_REGISTRATION_REJECTED)).hasSize(2);
    }

    @Test
    void accountReadRequiresBearerAndNeverEnumeratesOtherAccounts() throws Exception {
        UUID aliceId = registerAccount("alice@example.com", "Alice", PASSWORD, "register-alice");
        UUID bobId = registerAccount("bob@example.com", "Bob", PASSWORD, "register-bob");
        String aliceToken = login("alice@example.com", PASSWORD);

        mockMvc.perform(get("/api/v1/accounts/{id}", aliceId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(aliceId.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.com"));

        String foreignBody = mockMvc.perform(get("/api/v1/accounts/{id}", bobId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mockMvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(foreignBody).doesNotContain("bob@example.com").doesNotContain(bobId.toString());
        assertThat(auditEvents(SecurityAuditEventType.ACCOUNT_READ_DENIED))
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.getAccountId()).isEqualTo(aliceId));

        mockMvc.perform(get("/api/v1/accounts/{id}", aliceId))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("alice@example.com"))));
    }

    @Test
    void replacesUnsafeCorrelationIdWithGeneratedValue() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/00000000-0000-0000-0000-000000000000")
                        .header("X-Correlation-ID", "contains spaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void preservesAValidatedCorrelationIdFromTheGateway() throws Exception {
        String correlationId = "11111111-1111-4111-8111-111111111111";

        mockMvc.perform(get("/api/v1/accounts/00000000-0000-0000-0000-000000000000")
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-ID", correlationId));
    }

    @Test
    void exposesHealthReadinessLivenessAndPrometheusEndpoints() {
        ResponseEntity<String> health = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health", String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertTrue(health.getBody().contains("\"status\":\"UP\""));

        ResponseEntity<String> liveness = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health/liveness", String.class);
        assertEquals(HttpStatus.OK, liveness.getStatusCode());

        ResponseEntity<String> readiness = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, readiness.getStatusCode());

        ResponseEntity<String> prometheus = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, prometheus.getStatusCode());
        assertTrue(prometheus.getBody().contains("application_ready_time_seconds"));
    }

    private UUID registerAccount(String email, String displayName, String password, String idempotencyKey)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationRequest(email, displayName, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return accountId(result);
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequest(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .required("accessToken")
                .asText();
    }

    private UUID accountId(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .required("id")
                .asText());
    }

    private String registrationRequest(String email, String displayName, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "email", email,
                "displayName", displayName,
                "password", password));
    }

    private String loginRequest(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private java.util.List<SecurityAuditEvent> auditEvents(SecurityAuditEventType eventType) {
        return auditRepository.findAll().stream()
                .filter(event -> event.getEventType() == eventType)
                .toList();
    }
}

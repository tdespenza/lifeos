package com.lifeos.identity.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * HTTP integration tests for the first-party login boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository accountRepository;

    @Autowired
    private PasswordCredentialRepository credentialRepository;

    @Autowired
    private AuthSessionRepository sessionRepository;

    @Autowired
    private SecurityAuditEventRepository auditRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private LoginRateLimiter rateLimiter;

    @BeforeEach
    void cleanDatabase() {
        reset(rateLimiter);
        auditRepository.deleteAll();
        sessionRepository.deleteAll();
        credentialRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void validCredentialsReturnSignedAccessTokenAndPersistSession() throws Exception {
        UserAccount account = provisionAccount("ada@example.com", "correct horse battery staple");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ADA@EXAMPLE.COM","password":"correct horse battery staple"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.sessionId").value(matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.accessToken").value(matchesPattern("[^.]+\\.[^.]+\\.[^.]+")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(300));

        org.assertj.core.api.Assertions.assertThat(sessionRepository.countActiveByAccountId(
                account.getId(), java.time.Instant.now())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(auditRepository.count()).isEqualTo(1);
    }

    @Test
    void whitespaceWrappedEmailIsTrimmedBeforeValidationAndNormalization() throws Exception {
        provisionAccount("ada@example.com", "correct horse battery staple");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"  ADA@EXAMPLE.COM  ","password":"correct horse battery staple"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void unknownAndWrongPasswordUseIdenticalGenericFailureBody() throws Exception {
        provisionAccount("ada@example.com", "correct horse battery staple");

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"unknown@example.com","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(unknownEmail).isEqualTo(wrongPassword);
        org.assertj.core.api.Assertions.assertThat(unknownEmail)
                .doesNotContain("unknown@example.com")
                .doesNotContain("ada@example.com")
                .doesNotContain("wrong");
    }

    @Test
    void malformedRequestUsesGenericFailureWithoutEchoingPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"super-secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("The supplied credentials could not be verified.")))
                .andExpect(content().string(not(containsString("super-secret"))))
                .andExpect(content().string(not(containsString("not-an-email"))));
    }

    @Test
    void missingEmailUsesGenericValidationFailureWithoutEchoingPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"super-secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("The supplied credentials could not be verified.")))
                .andExpect(content().string(not(containsString("super-secret"))));
    }

    @Test
    void rateLimitReturnsBoundedResponseAndDoesNotEvaluateCredentials() throws Exception {
        doThrow(new LoginRateLimitExceededException(60)).when(rateLimiter)
                .check(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"wrong"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().string(not(containsString("ada@example.com"))));
    }

    @Test
    void redisDependencyFailureReturnsGenericTemporaryFailure() throws Exception {
        doThrow(new AuthenticationDependencyUnavailableException()).when(rateLimiter)
                .check(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"wrong"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("Authentication is temporarily unavailable.")))
                .andExpect(content().string(not(containsString("ada@example.com"))));
    }

    @Test
    void disabledAccountUsesGenericFailure() throws Exception {
        UserAccount account = provisionAccount("ada@example.com", "correct horse battery staple");
        account.disable();
        accountRepository.saveAndFlush(account);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"correct horse battery staple"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("The supplied credentials could not be verified.")));
    }

    /**
     * Persists an account and its Argon2id credential for an integration scenario.
     *
     * @param email account email
     * @param password raw fixture password
     * @return persisted account
     */
    private UserAccount provisionAccount(String email, String password) {
        UserAccount account = accountRepository.saveAndFlush(new UserAccount(email, "Ada Lovelace"));
        credentialRepository.saveAndFlush(new PasswordCredential(account, passwordEncoder.encode(password)));
        return account;
    }
}

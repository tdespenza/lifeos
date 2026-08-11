package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies OIDC callback replay protection and the explicit account-linking policy.
 */
@ExtendWith(MockitoExtension.class)
class OidcAuthenticationServiceTest {

    private static final String PROVIDER_NAME = "example";
    private static final String VERIFIER = "a-verifier-with-43-characters-012345678901234";
    private static final String BROWSER_TRANSACTION = "b".repeat(43);
    private static final String OTHER_BROWSER_TRANSACTION = "c".repeat(43);

    @Mock
    private OidcStateStore stateStore;

    @Mock
    private OidcProviderClient providerClient;

    @Mock
    private ExternalIdentityRepository externalIdentityRepository;

    @Mock
    private UserAccountRepository accountRepository;

    @Mock
    private SessionTokenAuthority sessionTokenAuthority;

    @Mock
    private SecurityAuditService auditService;

    @Mock
    private LoginMetrics metrics;

    private IdentityAuthProperties properties;
    private OidcAuthenticationService service;

    @BeforeEach
    void setUp() {
        properties = new IdentityAuthProperties();
        IdentityAuthProperties.Provider provider = new IdentityAuthProperties.Provider();
        provider.setIssuer("https://issuer.example");
        provider.setAuthorizationUri("https://issuer.example/authorize");
        provider.setTokenUri("https://issuer.example/token");
        provider.setJwkSetUri("https://issuer.example/jwks");
        provider.setClientId("lifeos-client");
        provider.setClientSecret("provider-secret");
        provider.setRedirectUri("https://lifeos.example/api/v1/auth/oidc/example/callback");
        properties.getOidc().getProviders().put(PROVIDER_NAME, provider);
        service = new OidcAuthenticationService(
                properties, stateStore, providerClient, externalIdentityRepository, accountRepository,
                sessionTokenAuthority, auditService, metrics);
    }

    @Test
    void beginStoresSingleUseStateAndBuildsProviderRedirectWithPkceAndNonce() {
        URIAssertions redirect = new URIAssertions(service.begin(
                PROVIDER_NAME,
                new OidcAuthorizationRequest(codeChallenge(VERIFIER), "S256")));

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<OidcAuthorizationState> stateValueCaptor =
                ArgumentCaptor.forClass(OidcAuthorizationState.class);
        verify(stateStore).save(stateCaptor.capture(), stateValueCaptor.capture(), any());

        assertThat(stateCaptor.getValue()).hasSize(43);
        assertThat(stateValueCaptor.getValue().provider()).isEqualTo(PROVIDER_NAME);
        assertThat(stateValueCaptor.getValue().codeChallenge()).isEqualTo(codeChallenge(VERIFIER));
        assertThat(stateValueCaptor.getValue().nonce()).hasSize(43);
        assertThat(redirect.value()).contains("response_type=code");
        assertThat(redirect.value()).contains("code_challenge_method=S256");
        assertThat(redirect.value()).contains("client_id=lifeos-client");
    }

    @Test
    void browserAuthorizationStartBindsVerifierToTransactionWithoutAddingItToProviderRedirect() {
        OidcAuthenticationService.BrowserAuthorizationStart authorizationStart = service.beginBrowser(
                PROVIDER_NAME,
                new OidcAuthorizationRequest(codeChallenge(VERIFIER), "S256"),
                VERIFIER);

        ArgumentCaptor<OidcAuthorizationState> stateValueCaptor =
                ArgumentCaptor.forClass(OidcAuthorizationState.class);
        verify(stateStore).save(any(), stateValueCaptor.capture(), any());

        assertThat(stateValueCaptor.getValue().codeVerifier()).isEqualTo(VERIFIER);
        assertThat(stateValueCaptor.getValue().browserTransactionHash())
                .isEqualTo(browserTransactionHash(authorizationStart.browserTransaction()));
        assertThat(authorizationStart.state()).hasSize(43);
        assertThat(authorizationStart.browserTransaction()).hasSize(43);
        assertThat(authorizationStart.authorizationUri().toString()).doesNotContain("code_verifier");
    }

    @Test
    void successfulCallbackCreatesNewAccountAndSharedOidcSession() {
        UserAccount account = account();
        LoginResponse response = new LoginResponse(UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.of(state()));
        when(providerClient.exchangeAndValidate(
                any(), eq("code"), eq(VERIFIER), eq("nonce")))
                .thenReturn(new OidcIdentity("subject-1", "Ada@Example.com", "Ada Lovelace"));
        when(externalIdentityRepository.findByProviderAndSubject(PROVIDER_NAME, "subject-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());
        when(accountRepository.saveAndFlush(any(UserAccount.class))).thenReturn(account);
        when(sessionTokenAuthority.createSession(account, SessionAuthenticationMethod.OIDC))
                .thenReturn(response);

        assertThat(service.callback(PROVIDER_NAME, "code", "state", VERIFIER, null, "127.0.0.1"))
                .isEqualTo(response);

        verify(externalIdentityRepository).saveAndFlush(any(ExternalIdentity.class));
        verify(sessionTokenAuthority).createSession(account, SessionAuthenticationMethod.OIDC);
        verify(auditService).recordWithinCurrentTransaction(
                SecurityAuditEventType.OIDC_LOGIN_SUCCEEDED, account.getId(), "127.0.0.1");
        verify(metrics).record(SecurityAuditEventType.OIDC_LOGIN_SUCCEEDED);
    }

    @Test
    void callbackUsesVerifierRetainedInServerSideStateForBrowserRedirect() {
        UserAccount account = account();
        LoginResponse response = new LoginResponse(UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(stateStore.consume(eq("state"), eq(browserTransactionHash(BROWSER_TRANSACTION))))
                .thenReturn(Optional.of(stateWithVerifier()));
        when(providerClient.exchangeAndValidate(
                any(), eq("code"), eq(VERIFIER), eq("nonce")))
                .thenReturn(new OidcIdentity("subject-1", "ada@example.com", "Ada Lovelace"));
        when(externalIdentityRepository.findByProviderAndSubject(PROVIDER_NAME, "subject-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());
        when(accountRepository.saveAndFlush(any(UserAccount.class))).thenReturn(account);
        when(sessionTokenAuthority.createSession(account, SessionAuthenticationMethod.OIDC))
                .thenReturn(response);

        assertThat(service.callback(
                PROVIDER_NAME, "code", "state", null, null, BROWSER_TRANSACTION, "127.0.0.1"))
                .isEqualTo(response);

        verify(providerClient).exchangeAndValidate(
                any(), eq("code"), eq(VERIFIER), eq("nonce"));
    }

    @Test
    void rejectsCrossBrowserCallbackInjectionBeforeProviderExchange() {
        when(stateStore.consume(eq("state"), eq(browserTransactionHash(OTHER_BROWSER_TRANSACTION))))
                .thenReturn(Optional.of(stateWithVerifier()));

        assertThatThrownBy(() -> service.callback(
                PROVIDER_NAME,
                "attacker-code",
                "state",
                null,
                null,
                OTHER_BROWSER_TRANSACTION,
                "127.0.0.1"))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        verify(providerClient, never()).exchangeAndValidate(any(), any(), any(), any());
        verify(sessionTokenAuthority, never()).createSession(any(), any());
        verify(auditService).record(
                SecurityAuditEventType.OIDC_CALLBACK_REJECTED, null, "127.0.0.1");
    }

    @Test
    void expiredOrReusedCallbackIsRejectedBeforeProviderExchange() {
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.callback(
                PROVIDER_NAME, "code", "state", VERIFIER, null, "127.0.0.1"))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        verify(providerClient, never()).exchangeAndValidate(any(), any(), any(), any());
        verify(sessionTokenAuthority, never()).createSession(any(), any());
        verify(auditService).record(
                SecurityAuditEventType.OIDC_CALLBACK_REJECTED, null, "127.0.0.1");
    }

    @Test
    void mismatchedPkceVerifierIsRejectedAndCannotCreateAccountOrSession() {
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.of(state()));

        assertThatThrownBy(() -> service.callback(
                PROVIDER_NAME, "code", "state", "another-verifier-with-43-characters-012345678901", null,
                "127.0.0.1"))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        verify(providerClient, never()).exchangeAndValidate(any(), any(), any(), any());
        verify(accountRepository, never()).saveAndFlush(any());
        verify(auditService).record(
                SecurityAuditEventType.OIDC_CALLBACK_REJECTED, null, "127.0.0.1");
    }

    @Test
    void existingEmailIsNotAutomaticallyLinkedOrIssuedASession() {
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.of(state()));
        when(providerClient.exchangeAndValidate(any(), any(), any(), any()))
                .thenReturn(new OidcIdentity("subject-1", "ada@example.com", "Ada"));
        when(externalIdentityRepository.findByProviderAndSubject(PROVIDER_NAME, "subject-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account()));

        assertThatThrownBy(() -> service.callback(
                PROVIDER_NAME, "code", "state", VERIFIER, null, "127.0.0.1"))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        verify(accountRepository, never()).saveAndFlush(any());
        verify(externalIdentityRepository, never()).saveAndFlush(any());
        verify(sessionTokenAuthority, never()).createSession(any(), any());
        verify(auditService).record(
                SecurityAuditEventType.OIDC_CALLBACK_REJECTED, null, "127.0.0.1");
    }

    @Test
    void returningUserReusesExistingExternalIdentityWithoutCreatingAnotherAccount() {
        UserAccount account = account();
        LoginResponse response = new LoginResponse(UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.of(state()));
        when(providerClient.exchangeAndValidate(any(), any(), any(), any()))
                .thenReturn(new OidcIdentity("subject-1", "ada@example.com", "Ada Lovelace"));
        when(externalIdentityRepository.findByProviderAndSubject(PROVIDER_NAME, "subject-1"))
                .thenReturn(Optional.of(new ExternalIdentity(PROVIDER_NAME, "subject-1", account.getId())));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(sessionTokenAuthority.createSession(account, SessionAuthenticationMethod.OIDC))
                .thenReturn(response);

        assertThat(service.callback(PROVIDER_NAME, "code", "state", VERIFIER, null, "127.0.0.1"))
                .isEqualTo(response);

        verify(accountRepository, never()).findByEmail(any());
        verify(accountRepository, never()).saveAndFlush(any());
        verify(externalIdentityRepository, never()).saveAndFlush(any());
        verify(sessionTokenAuthority).createSession(account, SessionAuthenticationMethod.OIDC);
    }

    @Test
    void rejectsExternalIdentityWhenLinkedAccountNoLongerExists() {
        UUID accountId = UUID.randomUUID();
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.of(state()));
        when(providerClient.exchangeAndValidate(any(), any(), any(), any()))
                .thenReturn(new OidcIdentity("subject-1", "ada@example.com", "Ada Lovelace"));
        when(externalIdentityRepository.findByProviderAndSubject(PROVIDER_NAME, "subject-1"))
                .thenReturn(Optional.of(new ExternalIdentity(PROVIDER_NAME, "subject-1", accountId)));
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.callback(
                PROVIDER_NAME, "code", "state", VERIFIER, null, "127.0.0.1"))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        verify(sessionTokenAuthority, never()).createSession(any(), any());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDisabledExternalIdentityAccountWithoutCreatingASession() {
        UserAccount account = account();
        account.disable();
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.of(state()));
        when(providerClient.exchangeAndValidate(any(), any(), any(), any()))
                .thenReturn(new OidcIdentity("subject-1", "ada@example.com", "Ada Lovelace"));
        when(externalIdentityRepository.findByProviderAndSubject(PROVIDER_NAME, "subject-1"))
                .thenReturn(Optional.of(new ExternalIdentity(PROVIDER_NAME, "subject-1", account.getId())));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.callback(
                PROVIDER_NAME, "code", "state", VERIFIER, null, "127.0.0.1"))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        verify(sessionTokenAuthority, never()).createSession(any(), any());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void classifiesConcurrentDuplicateEmailInsertAsAuthenticationFailure() {
        when(stateStore.consume(eq("state"), isNull())).thenReturn(Optional.of(state()));
        when(providerClient.exchangeAndValidate(any(), any(), any(), any()))
                .thenReturn(new OidcIdentity("subject-1", "ada@example.com", "Ada Lovelace"));
        when(externalIdentityRepository.findByProviderAndSubject(PROVIDER_NAME, "subject-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());
        when(accountRepository.saveAndFlush(any(UserAccount.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate email"));

        assertThatThrownBy(() -> service.callback(
                PROVIDER_NAME, "code", "state", VERIFIER, null, "127.0.0.1"))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        verify(externalIdentityRepository, never()).saveAndFlush(any());
        verify(sessionTokenAuthority, never()).createSession(any(), any());
    }

    private OidcAuthorizationState state() {
        return new OidcAuthorizationState(
                PROVIDER_NAME,
                "https://lifeos.example/api/v1/auth/oidc/example/callback",
                codeChallenge(VERIFIER),
                "S256",
                "nonce");
    }

    private OidcAuthorizationState stateWithVerifier() {
        return OidcAuthorizationState.forBrowserRedirect(
                PROVIDER_NAME,
                "https://lifeos.example/api/v1/auth/oidc/example/callback",
                codeChallenge(VERIFIER),
                "S256",
                "nonce",
                VERIFIER,
                browserTransactionHash(BROWSER_TRANSACTION));
    }

    private UserAccount account() {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String browserTransactionHash(String transaction) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(transaction.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record URIAssertions(java.net.URI uri) {
        private String value() {
            return uri.toString();
        }
    }
}

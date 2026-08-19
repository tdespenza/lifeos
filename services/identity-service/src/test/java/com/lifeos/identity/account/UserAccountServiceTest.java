package com.lifeos.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the thin account facade around durable public registration and lookup. */
@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock
    private UserAccountRepository repository;

    @Mock
    private AccountRegistrationIdempotencyService registrationService;

    @Test
    void registerDelegatesFirstPartyEnrollmentToTheDurableCoordinator() {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        List<String> keys = List.of("registration-key");
        AccountRegistrationResult expected = new AccountRegistrationResult(account, false);
        when(registrationService.createOrReplay(
                        "ada@example.com",
                        "Ada Lovelace",
                        "correct horse battery staple",
                        keys,
                        "127.0.0.1"))
                .thenReturn(expected);

        UserAccountService service = new UserAccountService(repository, registrationService);
        AccountRegistrationResult result = service.register(
                "ada@example.com",
                "Ada Lovelace",
                "correct horse battery staple",
                keys,
                "127.0.0.1");

        assertThat(result).isSameAs(expected);
        verify(registrationService).createOrReplay(
                "ada@example.com",
                "Ada Lovelace",
                "correct horse battery staple",
                keys,
                "127.0.0.1");
    }

    @Test
    void getByIdThrowsSanitizedNotFoundWhenAccountDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        UserAccountService service = new UserAccountService(repository, registrationService);

        assertThatThrownBy(() -> service.getById(missingId))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("The requested account is not available.");
    }
}

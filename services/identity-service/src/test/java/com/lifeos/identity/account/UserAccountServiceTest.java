package com.lifeos.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock
    private UserAccountRepository repository;

    @Test
    void registerSavesNewAccountWhenEmailIsNotTaken() {
        when(repository.existsByEmail("ada@example.com")).thenReturn(false);
        when(repository.saveAndFlush(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccountService service = new UserAccountService(repository);
        UserAccount saved = service.register("ada@example.com", "Ada Lovelace");

        assertThat(saved.getEmail()).isEqualTo("ada@example.com");
        assertThat(saved.getDisplayName()).isEqualTo("Ada Lovelace");
        verify(repository).saveAndFlush(any(UserAccount.class));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(repository.existsByEmail("ada@example.com")).thenReturn(true);

        UserAccountService service = new UserAccountService(repository);

        assertThatThrownBy(() -> service.register("ada@example.com", "Ada Lovelace"))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("An account already exists for the supplied email address.");
    }

    @Test
    void registerMapsConcurrentDatabaseUniquenessViolationToConflict() {
        when(repository.existsByEmail("ada@example.com")).thenReturn(false);
        when(repository.saveAndFlush(any(UserAccount.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate email",
                        new ConstraintViolationException("duplicate email", null, "uk_user_account_email")));

        UserAccountService service = new UserAccountService(repository);

        assertThatThrownBy(() -> service.register("ada@example.com", "Ada Lovelace"))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("An account already exists for the supplied email address.");
    }

    @Test
    void getByIdThrowsWhenAccountDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        UserAccountService service = new UserAccountService(repository);

        assertThatThrownBy(() -> service.getById(missingId))
                .isInstanceOf(NoSuchElementException.class);
    }
}

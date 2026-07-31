package com.lifeos.identity.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class UserAccountRepositoryTest {

    @Autowired
    private UserAccountRepository repository;

    @Test
    void findByEmailReturnsPersistedAccount() {
        repository.save(new UserAccount("grace@example.com", "Grace Hopper"));

        assertThat(repository.findByEmail("grace@example.com"))
                .isPresent()
                .get()
                .satisfies(account -> assertThat(account.getDisplayName()).isEqualTo("Grace Hopper"));
    }

    @Test
    void existsByEmailIsFalseForUnknownEmail() {
        assertThat(repository.existsByEmail("nobody@example.com")).isFalse();
    }
}

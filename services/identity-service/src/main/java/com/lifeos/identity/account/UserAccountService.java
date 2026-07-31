package com.lifeos.identity.account;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserAccountRepository repository;

    public UserAccountService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserAccount register(String email, String displayName) {
        if (repository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        return repository.save(new UserAccount(email, displayName));
    }

    @Transactional(readOnly = true)
    public UserAccount getById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No account with id: " + id));
    }
}

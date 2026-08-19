package com.lifeos.documentvault.authorization;

/** Authentication boundary supplied by identity-service before local owner-scope enforcement. */
public interface DocumentVaultAccessService {

    DocumentVaultSubject authenticate(String authorizationHeader);

    void authorize(DocumentVaultSubject subject, String action, DocumentVaultAuthorizationResource resource);
}

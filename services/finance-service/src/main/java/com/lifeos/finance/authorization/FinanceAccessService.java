package com.lifeos.finance.authorization;

/** Authentication and action-policy boundary owned by identity-service. */
public interface FinanceAccessService {

    FinanceSubject authenticate(String authorizationHeader);

    void authorize(FinanceSubject subject, String action, FinanceAuthorizationResource resource);
}

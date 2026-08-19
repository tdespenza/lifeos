package com.lifeos.trustledger.access;

/** Fail-closed identity authentication and authorization boundary for public proof endpoints. */
public interface TrustAccessService {

    TrustSubject authenticate(String authorizationHeader);

    void authorize(TrustSubject subject, String action, TrustAuthorizationResource resource);
}

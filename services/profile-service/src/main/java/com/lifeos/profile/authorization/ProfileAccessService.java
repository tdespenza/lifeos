package com.lifeos.profile.authorization;

/** Authentication and action-policy boundary owned by identity-service. */
public interface ProfileAccessService {

    ProfileSubject authenticate(String authorizationHeader);

    void authorize(ProfileSubject subject, String action, ProfileAuthorizationResource resource);
}

package com.lifeos.media.authorization;

/** Authentication and exact V2 decision boundary owned by Identity. */
public interface MediaAccessService {

    MediaSubject authenticate(String authorizationHeader);

    void authorize(MediaSubject subject, String action, MediaAuthorizationResource resource);
}

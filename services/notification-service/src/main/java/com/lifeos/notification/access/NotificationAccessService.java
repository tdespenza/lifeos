package com.lifeos.notification.access;

/** Identity validation boundary for self-owned notification endpoints, reads, and streams. */
public interface NotificationAccessService {

    NotificationSubject authenticate(String authorizationHeader);
}

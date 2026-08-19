package com.lifeos.calendar.authorization;

/** Authentication and authorization policy boundary owned by Identity. */
public interface CalendarAccessService {

    CalendarSubject authenticate(String authorizationHeader);

    void authorize(CalendarSubject subject, String action, CalendarAuthorizationResource resource);
}

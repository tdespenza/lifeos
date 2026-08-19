package com.lifeos.assistant.authorization;

/** Bounded fail-closed authentication boundary backed by Identity. */
public interface AssistantAccessService {

    AssistantSubject authenticate(String authorizationHeader);
}

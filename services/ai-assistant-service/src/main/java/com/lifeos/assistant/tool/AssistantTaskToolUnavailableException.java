package com.lifeos.assistant.tool;

/** TaskGoal could not safely complete the bounded task mutation. */
public class AssistantTaskToolUnavailableException extends RuntimeException {

    public AssistantTaskToolUnavailableException() {
        super("The task tool is temporarily unavailable");
    }

    public AssistantTaskToolUnavailableException(Throwable cause) {
        super("The task tool is temporarily unavailable", cause);
    }
}

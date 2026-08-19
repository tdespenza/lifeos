package com.lifeos.assistant.recommendation;

/** The bounded planning projection could not be read safely. */
public class AssistantRecommendationUnavailableException extends RuntimeException {

    public AssistantRecommendationUnavailableException() {
        super("Goal planning recommendations are temporarily unavailable");
    }

    public AssistantRecommendationUnavailableException(Throwable cause) {
        super("Goal planning recommendations are temporarily unavailable", cause);
    }
}

package com.lifeos.media.domain;

/** A media-session command is invalid from its durable status. */
public class MediaLifecycleTransitionException extends RuntimeException {

    public MediaLifecycleTransitionException(String operation) {
        super("Media session cannot transition through " + operation);
    }
}

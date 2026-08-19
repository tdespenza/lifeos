package com.lifeos.notification.persistence;

/** Durable processing state for one producer CloudEvent ID. */
public enum InboxEventState {
    RECEIVED,
    PROCESSED
}

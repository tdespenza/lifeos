package com.lifeos.notification.persistence;

/** Durable state of one caller-owned notification endpoint enrollment retry key. */
public enum EndpointRegistrationIdempotencyState {
    PENDING,
    COMPLETED
}

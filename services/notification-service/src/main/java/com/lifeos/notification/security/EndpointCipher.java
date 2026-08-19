package com.lifeos.notification.security;

/** Encrypts contact destinations at rest before they enter a notification endpoint aggregate. */
public interface EndpointCipher {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}

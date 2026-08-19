package com.lifeos.assistant.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic bounded embedding used until a reviewed model embedding adapter is deployed. */
public final class DocumentEmbedding {

    private DocumentEmbedding() {
    }

    public static float[] embed(String text, int dimensions) {
        if (text == null || text.isBlank() || dimensions < 8 || dimensions > 1024) {
            throw new IllegalArgumentException("text and dimensions must be bounded");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.strip().getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[dimensions];
            for (int index = 0; index < dimensions; index++) {
                int first = bytes[index % bytes.length] & 0xff;
                int second = bytes[(index * 7 + 3) % bytes.length] & 0xff;
                vector[index] = ((first / 255.0f) * 2.0f) - 1.0f + ((second / 255.0f) - 0.5f) * 0.05f;
            }
            return vector;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}

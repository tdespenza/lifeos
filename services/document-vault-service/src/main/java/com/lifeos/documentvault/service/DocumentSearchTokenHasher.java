package com.lifeos.documentvault.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Creates bounded, keyed content tokens for plain-text documents.
 *
 * <p>Searchable content is never persisted in plaintext. The local object-store adapter extracts
 * at most 64 KiB of UTF-8 text, bounded PDF/Office text, and safe image dimensions; this class
 * persists only HMAC-SHA-256 token digests. OCR is an optional local extraction boundary; other
 * binary extraction remains unavailable.
 */
final class DocumentSearchTokenHasher {

    private static final String DOMAIN = "lifeos-document-content-search-v1:";
    private static final int MAX_TOKENS = 256;

    private DocumentSearchTokenHasher() {
    }

    static String encode(String secret, String searchableText) {
        if (searchableText == null || searchableText.isBlank()) {
            return "";
        }
        Set<String> tokens = tokenize(searchableText);
        StringBuilder encoded = new StringBuilder(tokens.size() * 65 + 2).append(';');
        for (String token : tokens) {
            encoded.append(digest(secret, token)).append(';');
        }
        return encoded.toString();
    }

    static boolean containsAny(String secret, String encoded, String query) {
        if (encoded == null || encoded.isBlank()) {
            return false;
        }
        for (String token : tokenize(query)) {
            if (encoded.contains(';' + digest(secret, token) + ';')) {
                return true;
            }
        }
        return false;
    }

    private static String digest(String secret, String token) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal((DOMAIN + token).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private static Set<String> tokenize(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= 2) {
                tokens.add(raw);
                if (tokens.size() == MAX_TOKENS) {
                    break;
                }
            }
        }
        return tokens;
    }
}

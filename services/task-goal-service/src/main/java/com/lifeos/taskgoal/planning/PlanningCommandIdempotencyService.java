package com.lifeos.taskgoal.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.taskgoal.authorization.TaskSubject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded durable idempotency for habits, routines, milestones, and occurrence commands. */
@Service
public class PlanningCommandIdempotencyService {

    private static final int MAX_KEY_LENGTH = 128;

    private final PlanningCommandIdempotencyRepository repository;
    private final PlanningCommandIdempotencyTransactions transactions;
    private final ObjectMapper objectMapper;

    public PlanningCommandIdempotencyService(
            PlanningCommandIdempotencyRepository repository,
            PlanningCommandIdempotencyTransactions transactions,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T execute(
            TaskSubject subject,
            String operation,
            UUID resourceId,
            String idempotencyKey,
            String requestFingerprint,
            Class<T> responseType,
            Supplier<T> completion) {
        String keyHash = hashKey(idempotencyKey);
        PlanningCommandIdempotency command = transactions.findExisting(subject, operation, keyHash)
                .orElseGet(() -> reserve(subject, operation, resourceId, keyHash, requestFingerprint));
        if (!command.getFingerprint().equals(requestFingerprint)) {
            throw new PlanningIdempotencyConflictException();
        }
        if (command.isCompleted()) {
            return deserialize(command.getResponseSnapshot(), responseType);
        }
        T result = Objects.requireNonNull(completion.get(), "planning completion must not be null");
        command.complete(serialize(result));
        repository.saveAndFlush(command);
        return result;
    }

    private PlanningCommandIdempotency reserve(
            TaskSubject subject, String operation, UUID resourceId, String keyHash, String fingerprint) {
        try {
            return transactions.reserve(subject, operation, resourceId, keyHash, fingerprint);
        } catch (DataIntegrityViolationException exception) {
            return transactions.findExisting(subject, operation, keyHash)
                    .orElseThrow(() -> new PlanningIdempotencyUnavailableException(exception));
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new PlanningIdempotencyUnavailableException(exception);
        }
    }

    private <T> T deserialize(String value, Class<T> responseType) {
        try {
            return objectMapper.readValue(value, responseType);
        } catch (JsonProcessingException exception) {
            throw new PlanningIdempotencyUnavailableException(exception);
        }
    }

    private static String hashKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH
                || key.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new InvalidPlanningIdempotencyKeyException();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new PlanningIdempotencyUnavailableException(exception);
        }
    }
}

package com.lifeos.profile.api;

import com.lifeos.profile.authorization.ProfileSubject;
import com.lifeos.profile.config.ProfileAssistantProjectionProperties;
import com.lifeos.profile.domain.AiContextCategory;
import com.lifeos.profile.journal.JournalManagementService;
import com.lifeos.profile.service.ProfileManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Consent-gated, bounded journal projection for the assistant workload. */
@RestController
public class AssistantJournalProjectionController {

    static final String PATH = "/api/v1/internal/assistant/journals";
    static final String PERSONALIZATION_PATH = "/api/v1/internal/assistant/personalization";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final ProfileManagementService profileService;
    private final JournalManagementService journalService;
    private final ProfileAssistantProjectionProperties properties;

    public AssistantJournalProjectionController(
            ProfileManagementService profileService,
            JournalManagementService journalService,
            ProfileAssistantProjectionProperties properties) {
        this.profileService = profileService;
        this.journalService = journalService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    public ResponseEntity<JournalProjectionResponse> list(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @Valid @RequestBody JournalProjectionRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        ProfileSubject subject = new ProfileSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        AiPersonalizationResponse consent = profileService.getAiPersonalizationSettings(subject);
        if (!consent.consentGranted()
                || !consent.personalizationEnabled()
                || !consent.allowedContextCategories().contains(AiContextCategory.JOURNALS)) {
            throw new JournalProjectionNotAuthorizedException();
        }

        int remaining = request.maxCharacters();
        List<JournalProjectionEntry> entries = new java.util.ArrayList<>();
        for (JournalEntryResponse entry : journalService.list(subject, request.maxEntries())) {
            if (remaining <= 0) {
                break;
            }
            String content = bounded(entry.content(), remaining);
            remaining -= content.length();
            entries.add(new JournalProjectionEntry(
                    entry.id(),
                    entry.title(),
                    content,
                    entry.createdAt(),
                    entry.updatedAt(),
                    content.length() < entry.content().length()));
        }
        boolean truncated = entries.size() < request.maxEntries()
                || entries.stream().anyMatch(JournalProjectionEntry::truncated);
        return ResponseEntity.ok(new JournalProjectionResponse(entries, truncated, List.of()));
    }

    @PostMapping(PERSONALIZATION_PATH)
    public ResponseEntity<AiPersonalizationResponse> personalization(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @Valid @RequestBody PersonalizationProjectionRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        ProfileSubject subject = new ProfileSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        return ResponseEntity.ok(profileService.getAiPersonalizationSettings(subject));
    }

    @ExceptionHandler(AssistantJournalWorkloadUnauthorizedException.class)
    public ResponseEntity<Void> workloadUnauthorized(AssistantJournalWorkloadUnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(JournalProjectionNotAuthorizedException.class)
    public ResponseEntity<Void> notAuthorized(JournalProjectionNotAuthorizedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    private void requireWorkload(String workloadIdentity, String workloadToken) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            throw new AssistantJournalWorkloadUnauthorizedException();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String bounded(String value, int maxCharacters) {
        if (value.length() <= maxCharacters) {
            return value;
        }
        return value.substring(0, maxCharacters);
    }

    public record JournalProjectionRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank String authenticationMethod,
            @NotBlank @jakarta.validation.constraints.Size(min = 64, max = 64) String accessTokenProof,
            @Min(1) @Max(10) int maxEntries,
            @Min(256) @Max(16_384) int maxCharacters) {
    }

    public record JournalProjectionResponse(
            List<JournalProjectionEntry> entries, boolean truncated, List<String> limitations) {
    }

    public record PersonalizationProjectionRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank String authenticationMethod,
            @NotBlank @jakarta.validation.constraints.Size(min = 64, max = 64) String accessTokenProof) {
    }

    public record JournalProjectionEntry(
            UUID id, String title, String content, Instant createdAt, Instant updatedAt, boolean truncated) {
    }

    public static class AssistantJournalWorkloadUnauthorizedException extends RuntimeException {
    }

    public static class JournalProjectionNotAuthorizedException extends RuntimeException {
    }
}

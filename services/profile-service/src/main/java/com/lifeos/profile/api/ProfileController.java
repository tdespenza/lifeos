package com.lifeos.profile.api;

import com.lifeos.profile.audit.ProfileSecurityAuditEventType;
import com.lifeos.profile.audit.ProfileSecurityAuditService;
import com.lifeos.profile.authorization.ProfileAccessService;
import com.lifeos.profile.authorization.ProfileAuthenticationFailure;
import com.lifeos.profile.authorization.ProfileAuthorizationActions;
import com.lifeos.profile.authorization.ProfileAuthorizationDependencyUnavailable;
import com.lifeos.profile.authorization.ProfileAuthorizationResource;
import com.lifeos.profile.authorization.ProfileSubject;
import com.lifeos.profile.idempotency.ProfileCreatePrecondition;
import com.lifeos.profile.idempotency.ProfileIdempotencyKey;
import com.lifeos.profile.idempotency.ProfileVersionPrecondition;
import com.lifeos.profile.service.ProfileManagementService;
import com.lifeos.profile.service.ProfileMutationResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public Profile service HTTP contract. There is intentionally no account-ID profile lookup path. */
@RestController
public class ProfileController {

    private static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final ProfileManagementService service;
    private final ProfileAccessService accessService;
    private final ProfileSecurityAuditService auditService;
    private final com.lifeos.profile.journal.JournalManagementService journalService;

    public ProfileController(
            ProfileManagementService service,
            ProfileAccessService accessService,
            ProfileSecurityAuditService auditService,
            com.lifeos.profile.journal.JournalManagementService journalService) {
        this.service = service;
        this.accessService = accessService;
        this.auditService = auditService;
        this.journalService = journalService;
    }

    @PostMapping("/api/v1/profiles/me")
    public ResponseEntity<ProfileResponse> createProfile(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileCreatePrecondition.HEADER_NAME, required = false) List<String> ifNoneMatch,
            @Valid @RequestBody CreateProfileRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileCreatePrecondition.requireCreateOnly(ifNoneMatch);
        ProfileMutationResult<ProfileResponse> result = service.createProfile(
                subject, request, ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @GetMapping("/api/v1/profiles/me")
    public ResponseEntity<ProfileResponse> getProfile(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        ProfileResponse body = service.getProfile(authenticate(authorizationHeader));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/profiles/me")
    public ResponseEntity<ProfileResponse> updateProfile(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdateProfileRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileMutationResult<ProfileResponse> result = service.updateProfile(
                subject,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @GetMapping("/api/v1/profiles/me/preferences")
    public ResponseEntity<PreferencesResponse> getPreferences(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        PreferencesResponse body = service.getPreferences(authenticate(authorizationHeader));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/profiles/me/preferences")
    public ResponseEntity<PreferencesResponse> updatePreferences(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdatePreferencesRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileMutationResult<PreferencesResponse> result = service.updatePreferences(
                subject,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @GetMapping("/api/v1/profiles/me/privacy")
    public ResponseEntity<PrivacySettingsResponse> getPrivacySettings(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        PrivacySettingsResponse body = service.getPrivacySettings(authenticate(authorizationHeader));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/profiles/me/privacy")
    public ResponseEntity<PrivacySettingsResponse> updatePrivacySettings(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdatePrivacySettingsRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileMutationResult<PrivacySettingsResponse> result = service.updatePrivacySettings(
                subject,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @GetMapping("/api/v1/profiles/me/ai-personalization")
    public ResponseEntity<AiPersonalizationResponse> getAiPersonalizationSettings(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        AiPersonalizationResponse body = service.getAiPersonalizationSettings(authenticate(authorizationHeader));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/profiles/me/ai-personalization")
    public ResponseEntity<AiPersonalizationResponse> updateAiPersonalizationSettings(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdateAiPersonalizationRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileMutationResult<AiPersonalizationResponse> result = service.updateAiPersonalizationSettings(
                subject,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @PostMapping("/api/v1/profiles/me/journal")
    public ResponseEntity<JournalEntryResponse> createJournalEntry(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody CreateJournalEntryRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        authorizeJournal(subject, ProfileAuthorizationActions.PROFILE_UPDATE);
        var mutation = journalService.create(
                subject, ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys), request);
        JournalEntryResponse response = mutation.body();
        return ResponseEntity.created(java.net.URI.create("/api/v1/profiles/me/journal/" + response.id()))
                .eTag(etag(response.version()))
                .header(REPLAY_HEADER, Boolean.toString(mutation.replayed()))
                .body(response);
    }

    @GetMapping("/api/v1/profiles/me/journal")
    public List<JournalEntryResponse> listJournalEntries(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "20") int limit) {
        ProfileSubject subject = authenticate(authorizationHeader);
        authorizeJournal(subject, ProfileAuthorizationActions.PROFILE_READ);
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        return journalService.list(subject, limit);
    }

    @GetMapping("/api/v1/profiles/me/journal/{entryId}")
    public ResponseEntity<JournalEntryResponse> getJournalEntry(
            @PathVariable UUID entryId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        ProfileSubject subject = authenticate(authorizationHeader);
        authorizeJournal(subject, ProfileAuthorizationActions.PROFILE_READ);
        JournalEntryResponse response = journalService.get(subject, entryId);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @PutMapping("/api/v1/profiles/me/journal/{entryId}")
    public ResponseEntity<JournalEntryResponse> updateJournalEntry(
            @PathVariable UUID entryId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdateJournalEntryRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        authorizeJournal(subject, ProfileAuthorizationActions.PROFILE_UPDATE);
        var mutation = journalService.update(
                subject,
                entryId,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys),
                request);
        JournalEntryResponse response = mutation.body();
        return ResponseEntity.ok()
                .eTag(etag(response.version()))
                .header(REPLAY_HEADER, Boolean.toString(mutation.replayed()))
                .body(response);
    }

    @DeleteMapping("/api/v1/profiles/me/journal/{entryId}")
    public ResponseEntity<Void> deleteJournalEntry(
            @PathVariable UUID entryId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch) {
        ProfileSubject subject = authenticate(authorizationHeader);
        authorizeJournal(subject, ProfileAuthorizationActions.PROFILE_UPDATE);
        boolean replayed = journalService.delete(
                subject,
                entryId,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return ResponseEntity.noContent().header(REPLAY_HEADER, Boolean.toString(replayed)).build();
    }

    @PostMapping("/api/v1/households")
    public ResponseEntity<HouseholdResponse> createHousehold(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileCreatePrecondition.HEADER_NAME, required = false) List<String> ifNoneMatch,
            @Valid @RequestBody CreateHouseholdRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileCreatePrecondition.requireCreateOnly(ifNoneMatch);
        ProfileMutationResult<HouseholdResponse> result = service.createHousehold(
                subject, request, ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @GetMapping("/api/v1/households/{householdId}")
    public ResponseEntity<HouseholdResponse> getHousehold(
            @PathVariable UUID householdId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        HouseholdResponse body = service.getHousehold(authenticate(authorizationHeader), householdId);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @GetMapping("/api/v1/households/{householdId}/members")
    public List<HouseholdMemberResponse> listHouseholdMembers(
            @PathVariable UUID householdId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        return service.listHouseholdMembers(authenticate(authorizationHeader), householdId);
    }

    @PostMapping("/api/v1/households/{householdId}/members")
    public ResponseEntity<HouseholdResponse> addHouseholdMember(
            @PathVariable UUID householdId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody AddHouseholdMemberRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileMutationResult<HouseholdResponse> result = service.addHouseholdMember(
                subject,
                householdId,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @PutMapping("/api/v1/households/{householdId}/members/{accountId}/permissions")
    public ResponseEntity<HouseholdResponse> updateHouseholdMemberPermissions(
            @PathVariable UUID householdId,
            @PathVariable UUID accountId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdateHouseholdMemberPermissionsRequest request) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileMutationResult<HouseholdResponse> result = service.updateHouseholdMemberPermissions(
                subject,
                householdId,
                accountId,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    @DeleteMapping("/api/v1/households/{householdId}/members/{accountId}")
    public ResponseEntity<HouseholdResponse> removeHouseholdMember(
            @PathVariable UUID householdId,
            @PathVariable UUID accountId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = ProfileIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = ProfileVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch) {
        ProfileSubject subject = authenticate(authorizationHeader);
        ProfileMutationResult<HouseholdResponse> result = service.removeHouseholdMember(
                subject,
                householdId,
                accountId,
                ProfileVersionPrecondition.requireSingleHeader(ifMatch),
                ProfileIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result);
    }

    private ProfileSubject authenticate(String authorizationHeader) {
        try {
            return accessService.authenticate(authorizationHeader);
        } catch (ProfileAuthenticationFailure exception) {
            auditService.record(ProfileSecurityAuditEventType.AUTHENTICATION_FAILED, null, "AUTHENTICATION_FAILED");
            throw exception;
        } catch (ProfileAuthorizationDependencyUnavailable exception) {
            auditService.record(
                    ProfileSecurityAuditEventType.AUTHENTICATION_DEPENDENCY_UNAVAILABLE,
                    null,
                    "AUTHENTICATION_DEPENDENCY_UNAVAILABLE");
            throw exception;
        }
    }

    private void authorizeJournal(ProfileSubject subject, String action) {
        accessService.authorize(
                subject,
                action,
                ProfileAuthorizationResource.forExistingProfile(subject, subject.accountId()));
    }

    private static <T> ResponseEntity<T> mutationResponse(ProfileMutationResult<T> result) {
        long version = extractVersion(result.body());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.responseStatus())
                .eTag(etag(version))
                .header(REPLAY_HEADER, Boolean.toString(result.replayed()));
        if (result.responseLocation() != null) {
            response.header(HttpHeaders.LOCATION, result.responseLocation());
        }
        return response.body(result.body());
    }

    private static long extractVersion(Object body) {
        return switch (body) {
            case ProfileResponse response -> response.version();
            case PreferencesResponse response -> response.version();
            case PrivacySettingsResponse response -> response.version();
            case AiPersonalizationResponse response -> response.version();
            case HouseholdResponse response -> response.version();
            default -> throw new IllegalStateException("unsupported profile mutation response type");
        };
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

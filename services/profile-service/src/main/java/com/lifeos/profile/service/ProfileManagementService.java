package com.lifeos.profile.service;

import com.lifeos.profile.api.AddHouseholdMemberRequest;
import com.lifeos.profile.api.AiPersonalizationResponse;
import com.lifeos.profile.api.CreateHouseholdRequest;
import com.lifeos.profile.api.CreateProfileRequest;
import com.lifeos.profile.api.HouseholdMemberResponse;
import com.lifeos.profile.api.HouseholdResponse;
import com.lifeos.profile.api.PreferencesResponse;
import com.lifeos.profile.api.PrivacySettingsResponse;
import com.lifeos.profile.api.ProfileResponse;
import com.lifeos.profile.api.UpdateAiPersonalizationRequest;
import com.lifeos.profile.api.UpdateHouseholdMemberPermissionsRequest;
import com.lifeos.profile.api.UpdatePreferencesRequest;
import com.lifeos.profile.api.UpdatePrivacySettingsRequest;
import com.lifeos.profile.api.UpdateProfileRequest;
import com.lifeos.profile.audit.ProfileSecurityAuditEventType;
import com.lifeos.profile.audit.ProfileSecurityAuditService;
import com.lifeos.profile.authorization.ProfileAccessService;
import com.lifeos.profile.authorization.ProfileAuthorizationActions;
import com.lifeos.profile.authorization.ProfileAuthorizationDependencyUnavailable;
import com.lifeos.profile.authorization.ProfileAuthorizationDenied;
import com.lifeos.profile.authorization.ProfileAuthorizationResource;
import com.lifeos.profile.authorization.ProfileSubject;
import com.lifeos.profile.domain.Household;
import com.lifeos.profile.domain.HouseholdMember;
import com.lifeos.profile.domain.HouseholdMemberRepository;
import com.lifeos.profile.domain.HouseholdPermission;
import com.lifeos.profile.domain.HouseholdRepository;
import com.lifeos.profile.domain.PersonalProfile;
import com.lifeos.profile.domain.PersonalProfileRepository;
import com.lifeos.profile.domain.ProfileAiPersonalizationSettings;
import com.lifeos.profile.domain.ProfileAiPersonalizationSettingsRepository;
import com.lifeos.profile.domain.ProfilePreferences;
import com.lifeos.profile.domain.ProfilePreferencesRepository;
import com.lifeos.profile.domain.ProfilePrivacySettings;
import com.lifeos.profile.domain.ProfilePrivacySettingsRepository;
import com.lifeos.profile.idempotency.ProfileIdempotencyExecution;
import com.lifeos.profile.idempotency.ProfileIdempotencyUnavailableException;
import com.lifeos.profile.idempotency.ProfileMutationFingerprint;
import com.lifeos.profile.idempotency.ProfileMutationIdempotencyService;
import com.lifeos.profile.idempotency.ProfileMutationOperation;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Transactionally owns profiles, settings, household scopes, durable retries, and local ABAC.
 * Identity authenticates bearer subjects and authorizes exact action capability; this service
 * validates personal ownership plus each household membership's explicit permission set.
 */
@Service
public class ProfileManagementService {

    private static final Set<HouseholdPermission> OWNER_PERMISSIONS = Set.of(
            HouseholdPermission.HOUSEHOLD_READ,
            HouseholdPermission.MEMBERS_READ,
            HouseholdPermission.MEMBERS_MANAGE);

    private final PersonalProfileRepository profileRepository;
    private final ProfilePreferencesRepository preferencesRepository;
    private final ProfilePrivacySettingsRepository privacyRepository;
    private final ProfileAiPersonalizationSettingsRepository aiSettingsRepository;
    private final HouseholdRepository householdRepository;
    private final HouseholdMemberRepository householdMemberRepository;
    private final ProfileAccessService accessService;
    private final ProfileMutationIdempotencyService idempotencyService;
    private final ProfileMutationFingerprint mutationFingerprint;
    private final ProfileSecurityAuditService auditService;
    private final ProfileAuthorizationMetrics authorizationMetrics;

    public ProfileManagementService(
            PersonalProfileRepository profileRepository,
            ProfilePreferencesRepository preferencesRepository,
            ProfilePrivacySettingsRepository privacyRepository,
            ProfileAiPersonalizationSettingsRepository aiSettingsRepository,
            HouseholdRepository householdRepository,
            HouseholdMemberRepository householdMemberRepository,
            ProfileAccessService accessService,
            ProfileMutationIdempotencyService idempotencyService,
            ProfileMutationFingerprint mutationFingerprint,
            ProfileSecurityAuditService auditService,
            ProfileAuthorizationMetrics authorizationMetrics) {
        this.profileRepository = profileRepository;
        this.preferencesRepository = preferencesRepository;
        this.privacyRepository = privacyRepository;
        this.aiSettingsRepository = aiSettingsRepository;
        this.householdRepository = householdRepository;
        this.householdMemberRepository = householdMemberRepository;
        this.accessService = accessService;
        this.idempotencyService = idempotencyService;
        this.mutationFingerprint = mutationFingerprint;
        this.auditService = auditService;
        this.authorizationMetrics = authorizationMetrics;
    }

    public ProfileMutationResult<ProfileResponse> createProfile(
            ProfileSubject subject, CreateProfileRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        UUID candidateProfileId = UUID.randomUUID();
        authorize(subject, ProfileAuthorizationActions.PROFILE_CREATE,
                ProfileAuthorizationResource.forNewProfile(subject, candidateProfileId));
        String requestFingerprint = fingerprint(
                ProfileMutationOperation.CREATE_PROFILE,
                request.displayName(),
                request.locale(),
                request.timeZone(),
                request.pronouns(),
                request.bio());
        ProfileIdempotencyExecution<ProfileResponse> result;
        try {
            result = idempotencyService.execute(
                    subject,
                    ProfileMutationOperation.CREATE_PROFILE,
                    candidateProfileId,
                    -1,
                    idempotencyKey,
                    requestFingerprint,
                    ProfileResponse.class,
                    profileId -> createProfileWithinReservation(subject, profileId, request));
        } catch (ProfileIdempotencyUnavailableException exception) {
            if (!profileExists(subject)) {
                throw exception;
            }
            discardRejectedProfileCreation(subject, idempotencyKey, requestFingerprint);
            throw new ProfileAlreadyExistsException();
        }
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public ProfileResponse getProfile(ProfileSubject subject) {
        PersonalProfile profile = loadProfileForAccess(subject, ProfileAuthorizationActions.PROFILE_READ);
        return ProfileResponse.from(profile);
    }

    public ProfileMutationResult<ProfileResponse> updateProfile(
            ProfileSubject subject, long expectedVersion, UpdateProfileRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        PersonalProfile current = loadProfileForAccess(subject, ProfileAuthorizationActions.PROFILE_UPDATE);
        ProfileIdempotencyExecution<ProfileResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.UPDATE_PROFILE,
                current.getId(),
                expectedVersion,
                idempotencyKey,
                fingerprint(
                        ProfileMutationOperation.UPDATE_PROFILE,
                        current.getId().toString(),
                        Long.toString(expectedVersion),
                        request.displayName(),
                        request.locale(),
                        request.timeZone(),
                        request.pronouns(),
                        request.bio()),
                ProfileResponse.class,
                ignored -> updateProfileWithinReservation(subject, expectedVersion, request));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public PreferencesResponse getPreferences(ProfileSubject subject) {
        PersonalProfile profile = loadProfileForAccess(subject, ProfileAuthorizationActions.PREFERENCES_READ);
        return PreferencesResponse.from(loadPreferences(profile.getId()));
    }

    public ProfileMutationResult<PreferencesResponse> updatePreferences(
            ProfileSubject subject, long expectedVersion, UpdatePreferencesRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        PersonalProfile profile = loadProfileForAccess(subject, ProfileAuthorizationActions.PREFERENCES_UPDATE);
        ProfileIdempotencyExecution<PreferencesResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.UPDATE_PREFERENCES,
                profile.getId(),
                expectedVersion,
                idempotencyKey,
                fingerprint(
                        ProfileMutationOperation.UPDATE_PREFERENCES,
                        profile.getId().toString(),
                        Long.toString(expectedVersion),
                        request.theme().name(),
                        request.weekStart().name(),
                        Boolean.toString(request.dailyDigestEnabled()),
                        Integer.toString(request.defaultGoalHorizonDays())),
                PreferencesResponse.class,
                ignored -> updatePreferencesWithinReservation(subject, expectedVersion, request));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public PrivacySettingsResponse getPrivacySettings(ProfileSubject subject) {
        PersonalProfile profile = loadProfileForAccess(subject, ProfileAuthorizationActions.PRIVACY_READ);
        return PrivacySettingsResponse.from(loadPrivacy(profile.getId()));
    }

    public ProfileMutationResult<PrivacySettingsResponse> updatePrivacySettings(
            ProfileSubject subject, long expectedVersion, UpdatePrivacySettingsRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        PersonalProfile profile = loadProfileForAccess(subject, ProfileAuthorizationActions.PRIVACY_UPDATE);
        ProfileIdempotencyExecution<PrivacySettingsResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.UPDATE_PRIVACY,
                profile.getId(),
                expectedVersion,
                idempotencyKey,
                fingerprint(
                        ProfileMutationOperation.UPDATE_PRIVACY,
                        profile.getId().toString(),
                        Long.toString(expectedVersion),
                        request.profileVisibility().name(),
                        Boolean.toString(request.shareActivityWithHousehold()),
                        Boolean.toString(request.allowHouseholdDirectory())),
                PrivacySettingsResponse.class,
                ignored -> updatePrivacyWithinReservation(subject, expectedVersion, request));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public AiPersonalizationResponse getAiPersonalizationSettings(ProfileSubject subject) {
        PersonalProfile profile = loadProfileForAccess(subject, ProfileAuthorizationActions.AI_PERSONALIZATION_READ);
        return AiPersonalizationResponse.from(loadAiSettings(profile.getId()));
    }

    public ProfileMutationResult<AiPersonalizationResponse> updateAiPersonalizationSettings(
            ProfileSubject subject, long expectedVersion, UpdateAiPersonalizationRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        PersonalProfile profile = loadProfileForAccess(subject, ProfileAuthorizationActions.AI_PERSONALIZATION_UPDATE);
        ProfileIdempotencyExecution<AiPersonalizationResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.UPDATE_AI_PERSONALIZATION,
                profile.getId(),
                expectedVersion,
                idempotencyKey,
                fingerprint(
                        ProfileMutationOperation.UPDATE_AI_PERSONALIZATION,
                        profile.getId().toString(),
                        Long.toString(expectedVersion),
                        Boolean.toString(request.consentGranted()),
                        Boolean.toString(request.personalizationEnabled()),
                        categoryFingerprint(request.allowedContextCategories())),
                AiPersonalizationResponse.class,
                ignored -> updateAiSettingsWithinReservation(subject, expectedVersion, request));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public ProfileMutationResult<HouseholdResponse> createHousehold(
            ProfileSubject subject, CreateHouseholdRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        UUID candidateHouseholdId = UUID.randomUUID();
        authorize(subject, ProfileAuthorizationActions.HOUSEHOLD_CREATE,
                ProfileAuthorizationResource.forHouseholdCapability(subject, candidateHouseholdId));
        ProfileIdempotencyExecution<HouseholdResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.CREATE_HOUSEHOLD,
                candidateHouseholdId,
                -1,
                idempotencyKey,
                fingerprint(ProfileMutationOperation.CREATE_HOUSEHOLD, request.name()),
                HouseholdResponse.class,
                householdId -> createHouseholdWithinReservation(subject, householdId, request));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public HouseholdResponse getHousehold(ProfileSubject subject, UUID householdId) {
        return HouseholdResponse.from(loadHouseholdForScope(
                subject,
                householdId,
                ProfileAuthorizationActions.HOUSEHOLD_READ,
                HouseholdPermission.HOUSEHOLD_READ));
    }

    public List<HouseholdMemberResponse> listHouseholdMembers(ProfileSubject subject, UUID householdId) {
        loadHouseholdForScope(
                subject,
                householdId,
                ProfileAuthorizationActions.HOUSEHOLD_MEMBERS_READ,
                HouseholdPermission.MEMBERS_READ);
        return householdMemberRepository.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).stream()
                .map(HouseholdMemberResponse::from)
                .toList();
    }

    public ProfileMutationResult<HouseholdResponse> addHouseholdMember(
            ProfileSubject subject,
            UUID householdId,
            long expectedVersion,
            AddHouseholdMemberRequest request,
            String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        loadHouseholdForScope(
                subject,
                householdId,
                ProfileAuthorizationActions.HOUSEHOLD_MEMBERS_MANAGE,
                HouseholdPermission.MEMBERS_MANAGE);
        ProfileIdempotencyExecution<HouseholdResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.ADD_HOUSEHOLD_MEMBER,
                householdId,
                expectedVersion,
                idempotencyKey,
                fingerprint(
                        ProfileMutationOperation.ADD_HOUSEHOLD_MEMBER,
                        householdId.toString(),
                        Long.toString(expectedVersion),
                        request.accountId().toString(),
                        request.relationshipType().name(),
                        permissionFingerprint(request.permissions())),
                HouseholdResponse.class,
                ignored -> addHouseholdMemberWithinReservation(subject, householdId, expectedVersion, request));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public ProfileMutationResult<HouseholdResponse> updateHouseholdMemberPermissions(
            ProfileSubject subject,
            UUID householdId,
            UUID memberAccountId,
            long expectedVersion,
            UpdateHouseholdMemberPermissionsRequest request,
            String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        loadHouseholdForScope(
                subject,
                householdId,
                ProfileAuthorizationActions.HOUSEHOLD_MEMBERS_MANAGE,
                HouseholdPermission.MEMBERS_MANAGE);
        ProfileIdempotencyExecution<HouseholdResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.UPDATE_HOUSEHOLD_MEMBER,
                householdId,
                expectedVersion,
                idempotencyKey,
                fingerprint(
                        ProfileMutationOperation.UPDATE_HOUSEHOLD_MEMBER,
                        householdId.toString(),
                        Long.toString(expectedVersion),
                        memberAccountId.toString(),
                        permissionFingerprint(request.permissions())),
                HouseholdResponse.class,
                ignored -> updateMemberPermissionsWithinReservation(
                        subject, householdId, memberAccountId, expectedVersion, request));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    public ProfileMutationResult<HouseholdResponse> removeHouseholdMember(
            ProfileSubject subject,
            UUID householdId,
            UUID memberAccountId,
            long expectedVersion,
            String idempotencyKey) {
        loadHouseholdForScope(
                subject,
                householdId,
                ProfileAuthorizationActions.HOUSEHOLD_MEMBERS_MANAGE,
                HouseholdPermission.MEMBERS_MANAGE);
        ProfileIdempotencyExecution<HouseholdResponse> result = idempotencyService.execute(
                subject,
                ProfileMutationOperation.REMOVE_HOUSEHOLD_MEMBER,
                householdId,
                expectedVersion,
                idempotencyKey,
                fingerprint(
                        ProfileMutationOperation.REMOVE_HOUSEHOLD_MEMBER,
                        householdId.toString(),
                        Long.toString(expectedVersion),
                        memberAccountId.toString()),
                HouseholdResponse.class,
                ignored -> removeMemberWithinReservation(subject, householdId, memberAccountId, expectedVersion));
        recordMutation(subject, result.replayed());
        return toMutationResult(result);
    }

    private ProfileResponse createProfileWithinReservation(
            ProfileSubject subject, UUID profileId, CreateProfileRequest request) {
        if (profileRepository.findByOwnerAccountIdAndTenantId(subject.accountId(), subject.tenantId()).isPresent()) {
            throw new ProfileAlreadyExistsException();
        }
        PersonalProfile profile;
        try {
            profile = profileRepository.saveAndFlush(new PersonalProfile(
                    profileId,
                    subject.accountId(),
                    subject.tenantId(),
                    request.displayName(),
                    request.locale(),
                    request.timeZone(),
                    request.pronouns(),
                    request.bio()));
        } catch (DataIntegrityViolationException exception) {
            // The owner/tenant unique index is the cross-instance creation race arbiter. Mapping
            // the losing write preserves create-only semantics instead of leaking a transient 503.
            throw new ProfileAlreadyExistsException();
        }
        preferencesRepository.save(new ProfilePreferences(profile.getId()));
        privacyRepository.save(new ProfilePrivacySettings(profile.getId()));
        aiSettingsRepository.save(new ProfileAiPersonalizationSettings(profile.getId()));
        return ProfileResponse.from(profile);
    }

    private ProfileResponse updateProfileWithinReservation(
            ProfileSubject subject, long expectedVersion, UpdateProfileRequest request) {
        PersonalProfile profile = lockedProfile(subject);
        assertVersion(profile.getVersion(), expectedVersion);
        profile.update(request.displayName(), request.locale(), request.timeZone(), request.pronouns(), request.bio());
        return ProfileResponse.from(profileRepository.saveAndFlush(profile));
    }

    private PreferencesResponse updatePreferencesWithinReservation(
            ProfileSubject subject, long expectedVersion, UpdatePreferencesRequest request) {
        PersonalProfile profile = lockedProfile(subject);
        ProfilePreferences settings = preferencesRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(ProfileResourceNotFoundException::new);
        assertVersion(settings.getVersion(), expectedVersion);
        settings.update(
                request.theme(), request.weekStart(), request.dailyDigestEnabled(), request.defaultGoalHorizonDays());
        return PreferencesResponse.from(preferencesRepository.saveAndFlush(settings));
    }

    private PrivacySettingsResponse updatePrivacyWithinReservation(
            ProfileSubject subject, long expectedVersion, UpdatePrivacySettingsRequest request) {
        PersonalProfile profile = lockedProfile(subject);
        ProfilePrivacySettings settings = privacyRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(ProfileResourceNotFoundException::new);
        assertVersion(settings.getVersion(), expectedVersion);
        settings.update(
                request.profileVisibility(), request.shareActivityWithHousehold(), request.allowHouseholdDirectory());
        return PrivacySettingsResponse.from(privacyRepository.saveAndFlush(settings));
    }

    private AiPersonalizationResponse updateAiSettingsWithinReservation(
            ProfileSubject subject, long expectedVersion, UpdateAiPersonalizationRequest request) {
        PersonalProfile profile = lockedProfile(subject);
        ProfileAiPersonalizationSettings settings = aiSettingsRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(ProfileResourceNotFoundException::new);
        assertVersion(settings.getVersion(), expectedVersion);
        settings.update(
                request.consentGranted(), request.personalizationEnabled(), request.allowedContextCategories());
        return AiPersonalizationResponse.from(aiSettingsRepository.saveAndFlush(settings));
    }

    private HouseholdResponse createHouseholdWithinReservation(
            ProfileSubject subject, UUID householdId, CreateHouseholdRequest request) {
        if (householdRepository.existsById(householdId)) {
            throw new IllegalStateException("unexpected household identifier collision");
        }
        Household household = householdRepository.save(new Household(
                householdId, subject.accountId(), subject.tenantId(), request.name()));
        householdMemberRepository.save(new HouseholdMember(
                householdId, subject.accountId(), com.lifeos.profile.domain.FamilyRelationshipType.SELF, OWNER_PERMISSIONS));
        return HouseholdResponse.from(householdRepository.saveAndFlush(household));
    }

    private HouseholdResponse addHouseholdMemberWithinReservation(
            ProfileSubject subject,
            UUID householdId,
            long expectedVersion,
            AddHouseholdMemberRequest request) {
        Household household = lockedScopedHousehold(subject, householdId, HouseholdPermission.MEMBERS_MANAGE);
        assertVersion(household.getVersion(), expectedVersion);
        if (householdMemberRepository.findByHouseholdIdAndMemberAccountId(householdId, request.accountId()).isPresent()) {
            throw new HouseholdMemberConflictException();
        }
        try {
            householdMemberRepository.saveAndFlush(new HouseholdMember(
                    householdId, request.accountId(), request.relationshipType(), request.permissions()));
        } catch (DataIntegrityViolationException exception) {
            throw new HouseholdMemberConflictException();
        }
        household.touch();
        return HouseholdResponse.from(householdRepository.saveAndFlush(household));
    }

    private HouseholdResponse updateMemberPermissionsWithinReservation(
            ProfileSubject subject,
            UUID householdId,
            UUID memberAccountId,
            long expectedVersion,
            UpdateHouseholdMemberPermissionsRequest request) {
        Household household = lockedScopedHousehold(subject, householdId, HouseholdPermission.MEMBERS_MANAGE);
        assertVersion(household.getVersion(), expectedVersion);
        if (household.getOwnerAccountId().equals(memberAccountId)) {
            throw new HouseholdMemberConflictException();
        }
        HouseholdMember member = householdMemberRepository.findByHouseholdIdAndMemberAccountId(householdId, memberAccountId)
                .orElseThrow(ProfileResourceNotFoundException::new);
        member.updatePermissions(request.permissions());
        householdMemberRepository.saveAndFlush(member);
        household.touch();
        return HouseholdResponse.from(householdRepository.saveAndFlush(household));
    }

    private HouseholdResponse removeMemberWithinReservation(
            ProfileSubject subject, UUID householdId, UUID memberAccountId, long expectedVersion) {
        Household household = lockedScopedHousehold(subject, householdId, HouseholdPermission.MEMBERS_MANAGE);
        assertVersion(household.getVersion(), expectedVersion);
        if (household.getOwnerAccountId().equals(memberAccountId)) {
            throw new HouseholdMemberConflictException();
        }
        HouseholdMember member = householdMemberRepository.findByHouseholdIdAndMemberAccountId(householdId, memberAccountId)
                .orElseThrow(ProfileResourceNotFoundException::new);
        householdMemberRepository.delete(member);
        householdMemberRepository.flush();
        household.touch();
        return HouseholdResponse.from(householdRepository.saveAndFlush(household));
    }

    private PersonalProfile loadProfileForAccess(ProfileSubject subject, String action) {
        PersonalProfile profile = profileRepository.findByOwnerAccountIdAndTenantId(subject.accountId(), subject.tenantId())
                .orElse(null);
        authorize(
                subject,
                action,
                profile == null
                        ? ProfileAuthorizationResource.forMissingProfile(subject)
                        : ProfileAuthorizationResource.forExistingProfile(subject, profile.getId()));
        if (profile == null) {
            throw new ProfileResourceNotFoundException();
        }
        return profile;
    }

    private PersonalProfile lockedProfile(ProfileSubject subject) {
        PersonalProfile profile = profileRepository.findByOwnerAccountIdAndTenantIdForUpdate(subject.accountId(), subject.tenantId())
                .orElseThrow(ProfileResourceNotFoundException::new);
        if (!profile.getOwnerAccountId().equals(subject.accountId()) || !profile.getTenantId().equals(subject.tenantId())) {
            throw new ProfileResourceNotFoundException();
        }
        return profile;
    }

    private boolean profileExists(ProfileSubject subject) {
        try {
            return profileRepository.findByOwnerAccountIdAndTenantId(subject.accountId(), subject.tenantId()).isPresent();
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private void discardRejectedProfileCreation(
            ProfileSubject subject, String idempotencyKey, String requestFingerprint) {
        idempotencyService.discardPendingReservation(
                subject, ProfileMutationOperation.CREATE_PROFILE, idempotencyKey, requestFingerprint);
    }

    private ProfilePreferences loadPreferences(UUID profileId) {
        return preferencesRepository.findById(profileId).orElseThrow(ProfileResourceNotFoundException::new);
    }

    private ProfilePrivacySettings loadPrivacy(UUID profileId) {
        return privacyRepository.findById(profileId).orElseThrow(ProfileResourceNotFoundException::new);
    }

    private ProfileAiPersonalizationSettings loadAiSettings(UUID profileId) {
        return aiSettingsRepository.findById(profileId).orElseThrow(ProfileResourceNotFoundException::new);
    }

    private Household loadHouseholdForScope(
            ProfileSubject subject, UUID householdId, String action, HouseholdPermission permission) {
        authorize(subject, action, ProfileAuthorizationResource.forHouseholdCapability(subject, householdId));
        Household household = householdRepository.findById(householdId).orElse(null);
        return requireHouseholdScope(subject, household, permission);
    }

    private Household lockedScopedHousehold(ProfileSubject subject, UUID householdId, HouseholdPermission permission) {
        Household household = householdRepository.findByIdForUpdate(householdId).orElse(null);
        return requireHouseholdScope(subject, household, permission);
    }

    private Household requireHouseholdScope(ProfileSubject subject, Household household, HouseholdPermission permission) {
        if (household == null
                || household.getOwnerAccountId() == null
                || !household.getTenantId().equals(household.getOwnerAccountId().toString())) {
            recordHouseholdScope(subject, false);
            throw new ProfileResourceNotFoundException();
        }
        HouseholdMember membership = householdMemberRepository
                .findByHouseholdIdAndMemberAccountId(household.getId(), subject.accountId())
                .orElse(null);
        if (membership == null || !membership.permits(permission)) {
            recordHouseholdScope(subject, false);
            throw new ProfileResourceNotFoundException();
        }
        recordHouseholdScope(subject, true);
        return household;
    }

    private void authorize(ProfileSubject subject, String action, ProfileAuthorizationResource resource) {
        try {
            accessService.authorize(subject, action, resource);
        } catch (ProfileAuthorizationDenied exception) {
            authorizationMetrics.record(action, "denied");
            auditService.record(ProfileSecurityAuditEventType.AUTHORIZATION_DENIED, subject.accountId(), outcomeCode(action));
            throw exception;
        } catch (ProfileAuthorizationDependencyUnavailable exception) {
            authorizationMetrics.record(action, "unavailable");
            auditService.record(
                    ProfileSecurityAuditEventType.AUTHORIZATION_DEPENDENCY_UNAVAILABLE,
                    subject.accountId(),
                    outcomeCode(action));
            throw exception;
        }
        authorizationMetrics.record(action, "allowed");
        auditService.record(ProfileSecurityAuditEventType.AUTHORIZATION_ALLOWED, subject.accountId(), outcomeCode(action));
    }

    private void recordHouseholdScope(ProfileSubject subject, boolean allowed) {
        authorizationMetrics.record("household:local-scope", allowed ? "allowed" : "denied");
        auditService.record(
                allowed ? ProfileSecurityAuditEventType.HOUSEHOLD_SCOPE_ALLOWED : ProfileSecurityAuditEventType.HOUSEHOLD_SCOPE_DENIED,
                subject.accountId(),
                allowed ? "HOUSEHOLD_SCOPE_ALLOWED" : "HOUSEHOLD_SCOPE_DENIED");
    }

    private void recordMutation(ProfileSubject subject, boolean replayed) {
        auditService.record(
                replayed ? ProfileSecurityAuditEventType.MUTATION_REPLAYED : ProfileSecurityAuditEventType.MUTATION_COMPLETED,
                subject.accountId(),
                replayed ? "IDEMPOTENT_REPLAY" : "MUTATION_COMPLETED");
    }

    private String fingerprint(ProfileMutationOperation operation, String... values) {
        String[] parts = new String[values.length + 1];
        parts[0] = operation.name();
        System.arraycopy(values, 0, parts, 1, values.length);
        return mutationFingerprint.requestFingerprint(parts);
    }

    private static void assertVersion(long actualVersion, long expectedVersion) {
        if (actualVersion != expectedVersion) {
            throw new ProfileVersionConflictException();
        }
    }

    private static <T> ProfileMutationResult<T> toMutationResult(ProfileIdempotencyExecution<T> execution) {
        return new ProfileMutationResult<>(
                execution.value(), execution.replayed(), execution.responseStatus(), execution.responseLocation());
    }

    private static String categoryFingerprint(Set<?> categories) {
        return categories.stream().map(Object::toString).sorted().collect(Collectors.joining(","));
    }

    private static String permissionFingerprint(Set<HouseholdPermission> permissions) {
        return permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static String outcomeCode(String action) {
        return action.toUpperCase(java.util.Locale.ROOT).replace(':', '_').replace('-', '_');
    }
}

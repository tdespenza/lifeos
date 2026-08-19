package com.lifeos.profile.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.profile.api.CreateProfileRequest;
import com.lifeos.profile.authorization.ProfileAccessService;
import com.lifeos.profile.authorization.ProfileSubject;
import com.lifeos.profile.domain.PersonalProfileRepository;
import com.lifeos.profile.service.ProfileAlreadyExistsException;
import com.lifeos.profile.service.ProfileManagementService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL-only concurrency coverage for the unique reservation and exact-response snapshot. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "profile.idempotency-secret=postgres-idempotency-secret",
    "profile.audit-client-fingerprint-secret=postgres-audit-secret",
    "identity.workload-token=postgres-workload-token"
})
class ProfilePostgresIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lifeos_profile")
            .withUsername("lifeos")
            .withPassword("test-only-postgres-password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private ProfileManagementService service;

    @Autowired
    private PersonalProfileRepository profileRepository;

    @Autowired
    private ProfileMutationIdempotencyRepository idempotencyRepository;

    @MockitoBean
    private ProfileAccessService accessService;

    private ProfileSubject subject;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        profileRepository.deleteAll();
        subject = new ProfileSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void concurrentMatchingProfileCreatesConvergeOnOnePostgresProfileAndSnapshot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<UUID> first = executor.submit(() -> createAfterStart(ready, start));
            Future<UUID> second = executor.submit(() -> createAfterStart(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            UUID firstId = first.get(15, TimeUnit.SECONDS);
            UUID secondId = second.get(15, TimeUnit.SECONDS);

            assertThat(secondId).isEqualTo(firstId);
            assertThat(firstId).isNotEqualTo(subject.accountId());
            assertThat(profileRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentDifferentCreateKeysReturnOnePreconditionConflictWithoutAPendingReservation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CreateAttempt> first = executor.submit(() -> createAttemptAfterStart(ready, start, "profile-create-a"));
            Future<CreateAttempt> second = executor.submit(() -> createAttemptAfterStart(ready, start, "profile-create-b"));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CreateAttempt> attempts = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

            assertThat(attempts.stream().filter(CreateAttempt::succeeded).count()).isEqualTo(1L);
            Throwable rejection = attempts.stream()
                    .map(CreateAttempt::failure)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElseThrow();
            assertThat(rejection).isInstanceOf(ProfileAlreadyExistsException.class);
            assertThat(profileRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.findAll())
                    .hasSize(1)
                    .allMatch(ProfileMutationIdempotency::isCompleted);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private UUID createAfterStart(CountDownLatch ready, CountDownLatch start) throws Exception {
        return createAfterStart(ready, start, "postgres-concurrent-profile-create");
    }

    private UUID createAfterStart(CountDownLatch ready, CountDownLatch start, String idempotencyKey) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent PostgreSQL profile create did not receive its start signal");
        }
        return service.createProfile(
                        subject,
                        new CreateProfileRequest("Katherine Johnson", "en-US", "America/New_York", null, null),
                        idempotencyKey)
                .body()
                .id();
    }

    private CreateAttempt createAttemptAfterStart(CountDownLatch ready, CountDownLatch start, String idempotencyKey)
            throws Exception {
        try {
            return CreateAttempt.success(createAfterStart(ready, start, idempotencyKey));
        } catch (RuntimeException exception) {
            return CreateAttempt.failure(exception);
        }
    }

    private record CreateAttempt(UUID profileId, Throwable failure) {

        private static CreateAttempt success(UUID profileId) {
            return new CreateAttempt(profileId, null);
        }

        private static CreateAttempt failure(Throwable failure) {
            return new CreateAttempt(null, failure);
        }

        private boolean succeeded() {
            return profileId != null;
        }
    }
}

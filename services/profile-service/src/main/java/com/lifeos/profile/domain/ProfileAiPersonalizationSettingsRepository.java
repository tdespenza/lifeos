package com.lifeos.profile.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ProfileAiPersonalizationSettingsRepository
        extends JpaRepository<ProfileAiPersonalizationSettings, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select settings from ProfileAiPersonalizationSettings settings where settings.profileId = :profileId")
    Optional<ProfileAiPersonalizationSettings> findByProfileIdForUpdate(@Param("profileId") UUID profileId);
}

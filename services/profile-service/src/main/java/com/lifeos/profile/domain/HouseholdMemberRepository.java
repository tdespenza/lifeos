package com.lifeos.profile.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {

    Optional<HouseholdMember> findByHouseholdIdAndMemberAccountId(UUID householdId, UUID memberAccountId);

    List<HouseholdMember> findAllByHouseholdIdOrderByCreatedAtAsc(UUID householdId);
}

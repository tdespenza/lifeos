package com.lifeos.profile.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One opaque account relationship with a finite, explicitly granted household permission set. */
@Entity
@Table(
        name = "household_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_household_member_account",
                columnNames = {"household_id", "member_account_id"}))
public class HouseholdMember {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "member_account_id", nullable = false, updatable = false)
    private UUID memberAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 16)
    private FamilyRelationshipType relationshipType;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "household_member_permission", joinColumns = @JoinColumn(name = "household_member_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 32)
    private Set<HouseholdPermission> permissions = EnumSet.noneOf(HouseholdPermission.class);

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HouseholdMember() {
        // required by JPA
    }

    public HouseholdMember(
            UUID householdId,
            UUID memberAccountId,
            FamilyRelationshipType relationshipType,
            Set<HouseholdPermission> permissions) {
        id = UUID.randomUUID();
        this.householdId = Objects.requireNonNull(householdId, "householdId must not be null");
        this.memberAccountId = Objects.requireNonNull(memberAccountId, "memberAccountId must not be null");
        this.relationshipType = Objects.requireNonNull(relationshipType, "relationshipType must not be null");
        assignPermissions(permissions);
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        version = 0L;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getMemberAccountId() {
        return memberAccountId;
    }

    public FamilyRelationshipType getRelationshipType() {
        return relationshipType;
    }

    public Set<HouseholdPermission> getPermissions() {
        return Set.copyOf(permissions);
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean permits(HouseholdPermission permission) {
        return permissions.contains(permission);
    }

    public void updatePermissions(Set<HouseholdPermission> permissions) {
        assignPermissions(permissions);
        updatedAt = Instant.now();
    }

    private void assignPermissions(Set<HouseholdPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("a household membership needs at least one explicit permission");
        }
        this.permissions = EnumSet.copyOf(permissions);
    }
}

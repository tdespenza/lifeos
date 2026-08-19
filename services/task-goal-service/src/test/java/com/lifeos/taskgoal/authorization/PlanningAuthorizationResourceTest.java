package com.lifeos.taskgoal.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningAuthorizationResourceTest {

    @Test
    void objectAndCollectionFactsAreStrictAndImmutable() {
        UUID resourceId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        PlanningAuthorizationResource object = PlanningAuthorizationResource.forExisting(
                "habit", resourceId, ownerId, "tenant");
        PlanningAuthorizationResource collection = PlanningAuthorizationResource.forCollection("routine", "tenant");

        assertThat(object.attributes()).containsEntry("ownerAccountId", ownerId.toString())
                .containsEntry("resourceExists", "true");
        assertThat(collection.resourceId()).isNull();
        assertThat(collection.attributes()).isEmpty();
        assertThatThrownBy(() -> PlanningAuthorizationResource.forCollection("task", "tenant"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingObjectUsesZeroOwnerAndFalseExistence() {
        PlanningAuthorizationResource missing = PlanningAuthorizationResource.forMissing(
                "milestone", UUID.randomUUID(), "tenant");

        assertThat(missing.attributes()).containsEntry("resourceExists", "false")
                .containsEntry("ownerAccountId", "00000000-0000-0000-0000-000000000000");
    }
}

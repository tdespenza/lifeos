package com.lifeos.taskgoal.projection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Internal proof envelope for a bounded Calendar planning read. */
public record TaskGoalPlanningProjectionRequest(
        @NotNull UUID subjectId,
        @NotNull UUID sessionId,
        @NotBlank String authenticationMethod,
        @NotBlank String accessTokenProof,
        @NotBlank String resourceType,
        @NotBlank String resourceId) {}

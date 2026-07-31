package com.lifeos.taskgoal.goal.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateGoalRequest(@NotBlank String title) {
}

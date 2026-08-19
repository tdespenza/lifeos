package com.lifeos.taskgoal.planning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** HTTP DTOs for bounded habits, routines, milestones, and deterministic occurrence trends. */
public final class PlanningDtos {

    private PlanningDtos() {
    }

    public record CreateHabitRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull HabitCadence cadence,
            @NotBlank @Size(max = 64) String timeZone) {
    }

    public record UpdateHabitRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull HabitCadence cadence,
            @NotBlank @Size(max = 64) String timeZone) {
    }

    public record HabitResponse(
            UUID id,
            String name,
            HabitCadence cadence,
            String timeZone,
            boolean active,
            long version,
            LocalDate lastOccurrenceDate) {
    }

    public record RecordHabitOccurrenceRequest(@NotNull LocalDate occurrenceDate) {
    }

    public record HabitTrendResponse(
            UUID habitId,
            LocalDate from,
            LocalDate to,
            int expectedOccurrences,
            int completedOccurrences,
            int currentStreak,
            String status) {
    }

    public record CreateRoutineRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull RoutineCadence cadence,
            @Size(min = 1, max = 32) List<@NotBlank @Size(max = 120) String> activities) {
    }

    public record UpdateRoutineRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 64) String timeZone,
            @NotNull RoutineCadence cadence,
            @Size(min = 1, max = 32) List<@NotBlank @Size(max = 120) String> activities) {
    }

    public record RoutineResponse(
            UUID id,
            String name,
            String timeZone,
            RoutineCadence cadence,
            List<String> activities,
            long version) {
    }

    public record RoutineMaterializationResponse(UUID routineId, LocalDate from, LocalDate to, List<LocalDate> dates) {
    }

    public record CreateMilestoneRequest(
            @NotBlank @Size(max = 160) String title,
            @Size(max = 2000) String criteria,
            int position) {
    }

    public record UpdateMilestoneRequest(
            @NotBlank @Size(max = 160) String title,
            @Size(max = 2000) String criteria,
            int position) {
    }

    public record MilestoneResponse(
            UUID id,
            UUID goalId,
            String title,
            String criteria,
            int position,
            boolean completed,
            long version) {
    }
}

package com.lifeos.taskgoal.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.taskgoal.authorization.PlanningAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.GoalRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Application service for habits, routines, milestones, and bounded recurrence materialization. */
@Service
public class PlanningService {

    private static final int MAX_HABIT_TREND_DAYS = 366;
    private static final int MAX_MATERIALIZATION_DAYS = 366;

    private final HabitRepository habitRepository;
    private final HabitOccurrenceRepository occurrenceRepository;
    private final RoutineRepository routineRepository;
    private final RoutineOccurrenceRepository routineOccurrenceRepository;
    private final MilestoneRepository milestoneRepository;
    private final GoalRepository goalRepository;
    private final TaskAccessService accessService;
    private final PlanningCommandIdempotencyService idempotencyService;
    private final PlanningOccurrenceTransactions occurrenceTransactions;
    private final PlanningRoutineTransactions routineTransactions;
    private final ObjectMapper objectMapper;

    public PlanningService(
            HabitRepository habitRepository,
            HabitOccurrenceRepository occurrenceRepository,
            RoutineRepository routineRepository,
            RoutineOccurrenceRepository routineOccurrenceRepository,
            MilestoneRepository milestoneRepository,
            GoalRepository goalRepository,
            TaskAccessService accessService,
            PlanningCommandIdempotencyService idempotencyService,
            PlanningOccurrenceTransactions occurrenceTransactions,
            PlanningRoutineTransactions routineTransactions,
            ObjectMapper objectMapper) {
        this.habitRepository = habitRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.routineRepository = routineRepository;
        this.routineOccurrenceRepository = routineOccurrenceRepository;
        this.milestoneRepository = milestoneRepository;
        this.goalRepository = goalRepository;
        this.accessService = accessService;
        this.idempotencyService = idempotencyService;
        this.occurrenceTransactions = occurrenceTransactions;
        this.routineTransactions = routineTransactions;
        this.objectMapper = objectMapper;
    }

    public PlanningDtos.HabitResponse createHabit(
            TaskSubject subject, PlanningDtos.CreateHabitRequest request, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        accessService.authorize(subject, "habit:create",
                PlanningAuthorizationResource.forNew("habit", id, subject.accountId(), subject.tenantId()));
        String fingerprint = fingerprint("habit:create", request.name(), request.cadence().name(), request.timeZone());
        return idempotencyService.execute(subject, "HABIT_CREATE", id, idempotencyKey, fingerprint,
                PlanningDtos.HabitResponse.class, () -> {
                    Habit habit = habitRepository.saveAndFlush(new Habit(
                            id, request.name(), request.cadence(), request.timeZone(), subject.accountId(), subject.tenantId()));
                    return habitResponse(habit, null);
                });
    }

    public List<PlanningDtos.HabitResponse> listHabits(TaskSubject subject) {
        accessService.authorize(subject, "habit:list", PlanningAuthorizationResource.forCollection("habit", subject.tenantId()));
        return habitRepository.findByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(subject.accountId(), subject.tenantId())
                .stream().map(habit -> habitResponse(habit, lastOccurrence(habit, subject))).toList();
    }

    public PlanningDtos.HabitResponse getHabit(TaskSubject subject, UUID id) {
        Habit habit = loadHabit(subject, id, "habit:read");
        return habitResponse(habit, lastOccurrence(habit, subject));
    }

    public PlanningDtos.HabitResponse updateHabit(
            TaskSubject subject, UUID id, long expectedVersion, PlanningDtos.UpdateHabitRequest request, String key) {
        Habit habit = loadHabit(subject, id, "habit:update");
        if (habit.getVersion() != expectedVersion) {
            throw new PlanningVersionConflictException();
        }
        String fingerprint = fingerprint("habit:update", id.toString(), Long.toString(expectedVersion), request.name(),
                request.cadence().name(), request.timeZone());
        return idempotencyService.execute(subject, "HABIT_UPDATE", id, key, fingerprint,
                PlanningDtos.HabitResponse.class, () -> {
                    habit.update(request.name(), request.cadence(), request.timeZone());
                    return habitResponse(habitRepository.saveAndFlush(habit), lastOccurrence(habit, subject));
                });
    }

    public PlanningDtos.HabitResponse recordOccurrence(
            TaskSubject subject, UUID habitId, PlanningDtos.RecordHabitOccurrenceRequest request, String key) {
        Habit habit = loadHabit(subject, habitId, "habit:occurrence-create");
        LocalDate occurrenceDate = request.occurrenceDate();
        LocalDate today = LocalDate.now(ZoneId.of(habit.getTimeZone()));
        if (occurrenceDate.isAfter(today) || occurrenceDate.isBefore(today.minusDays(MAX_HABIT_TREND_DAYS))) {
            throw new IllegalArgumentException("occurrenceDate must be within the previous year and not in the future");
        }
        UUID occurrenceId = UUID.randomUUID();
        String fingerprint = fingerprint("habit:occurrence-create", habitId.toString(), occurrenceDate.toString());
        return idempotencyService.execute(subject, "HABIT_OCCURRENCE_CREATE", occurrenceId, key, fingerprint,
                PlanningDtos.HabitResponse.class, () -> {
                    if (occurrenceRepository.findByHabitIdAndOwnerAccountIdAndTenantIdAndOccurrenceDate(
                            habitId, subject.accountId(), subject.tenantId(), occurrenceDate).isEmpty()) {
                        try {
                            occurrenceTransactions.saveHabitOccurrence(
                                    occurrenceId, habitId, subject.accountId(), subject.tenantId(), occurrenceDate);
                        } catch (DataIntegrityViolationException ignored) {
                            // A concurrent same-date request has already committed the immutable event.
                        }
                    }
                    return habitResponse(habit, occurrenceDate);
                });
    }

    public PlanningDtos.HabitTrendResponse trend(TaskSubject subject, UUID id, LocalDate from, LocalDate to) {
        Habit habit = loadHabit(subject, id, "habit:trend-read");
        LocalDate end = to == null ? LocalDate.now(ZoneId.of(habit.getTimeZone())) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || start.plusDays(MAX_HABIT_TREND_DAYS).isBefore(end)) {
            throw new IllegalArgumentException("habit trend window must be 1 to 366 days");
        }
        List<HabitOccurrence> occurrences = occurrenceRepository
                .findByHabitIdAndOwnerAccountIdAndTenantIdAndOccurrenceDateBetweenOrderByOccurrenceDateAsc(
                        id, subject.accountId(), subject.tenantId(), start, end);
        int expected = expectedOccurrences(habit.getCadence(), start, end);
        int streak = currentStreak(habit, occurrences, end);
        String status = occurrences.isEmpty() ? "NO_DATA" : occurrences.size() < expected ? "PARTIAL" : "COMPLETE";
        return new PlanningDtos.HabitTrendResponse(id, start, end, expected, occurrences.size(), streak, status);
    }

    public PlanningDtos.RoutineResponse createRoutine(
            TaskSubject subject, PlanningDtos.CreateRoutineRequest request, String key) {
        UUID id = UUID.randomUUID();
        accessService.authorize(subject, "routine:create",
                PlanningAuthorizationResource.forNew("routine", id, subject.accountId(), subject.tenantId()));
        String fingerprint = fingerprint("routine:create", request.name(), request.timeZone(), request.cadence().name(),
                String.join("\u001f", request.activities()));
        return idempotencyService.execute(subject, "ROUTINE_CREATE", id, key, fingerprint,
                PlanningDtos.RoutineResponse.class, () -> routineResponse(routineRepository.saveAndFlush(new Routine(
                        id, request.name(), request.timeZone(), request.cadence(), request.activities(),
                        subject.accountId(), subject.tenantId(), objectMapper))));
    }

    public List<PlanningDtos.RoutineResponse> listRoutines(TaskSubject subject) {
        accessService.authorize(subject, "routine:list", PlanningAuthorizationResource.forCollection("routine", subject.tenantId()));
        return routineRepository.findByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(subject.accountId(), subject.tenantId())
                .stream().map(this::routineResponse).toList();
    }

    public PlanningDtos.RoutineResponse getRoutine(TaskSubject subject, UUID id) {
        return routineResponse(loadRoutine(subject, id, "routine:read"));
    }

    public PlanningDtos.RoutineResponse updateRoutine(
            TaskSubject subject, UUID id, long expectedVersion, PlanningDtos.UpdateRoutineRequest request, String key) {
        Routine routine = loadRoutine(subject, id, "routine:update");
        if (routine.getVersion() != expectedVersion) {
            throw new PlanningVersionConflictException();
        }
        String fingerprint = fingerprint("routine:update", id.toString(), Long.toString(expectedVersion), request.name(),
                request.timeZone(), request.cadence().name(), String.join("\u001f", request.activities()));
        return idempotencyService.execute(subject, "ROUTINE_UPDATE", id, key, fingerprint,
                PlanningDtos.RoutineResponse.class, () -> {
                    routine.update(request.name(), request.timeZone(), request.cadence(), request.activities(), objectMapper);
                    return routineResponse(routineRepository.saveAndFlush(routine));
                });
    }

    public PlanningDtos.RoutineMaterializationResponse materializeRoutine(
            TaskSubject subject, UUID id, LocalDate from, LocalDate to, String key) {
        Routine routine = loadRoutine(subject, id, "routine:materialize");
        LocalDate start = from == null ? LocalDate.now(ZoneId.of(routine.getTimeZone())) : from;
        LocalDate end = to == null ? start.plusDays(29) : to;
        if (start.isAfter(end) || start.plusDays(MAX_MATERIALIZATION_DAYS).isBefore(end)) {
            throw new IllegalArgumentException("routine materialization window must be 1 to 366 days");
        }
        String fingerprint = fingerprint("routine:materialize", id.toString(), start.toString(), end.toString());
        return idempotencyService.execute(subject, "ROUTINE_MATERIALIZE", id, key, fingerprint,
                PlanningDtos.RoutineMaterializationResponse.class, () -> {
                    List<LocalDate> created = new java.util.ArrayList<>();
                    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                        if (matches(routine.getCadence(), date, start)
                                && !routineOccurrenceRepository.existsByRoutineIdAndOwnerAccountIdAndTenantIdAndOccurrenceDate(
                                        id, subject.accountId(), subject.tenantId(), date)) {
                            try {
                                routineTransactions.saveOccurrence(
                                        UUID.randomUUID(), id, subject.accountId(), subject.tenantId(), date);
                                created.add(date);
                            } catch (DataIntegrityViolationException ignored) {
                                // A concurrent materialization already committed this immutable date.
                            }
                        }
                    }
                    routineOccurrenceRepository.flush();
                    return new PlanningDtos.RoutineMaterializationResponse(id, start, end, List.copyOf(created));
                });
    }

    public PlanningDtos.MilestoneResponse createMilestone(
            TaskSubject subject, UUID goalId, PlanningDtos.CreateMilestoneRequest request, String key) {
        requireOwnedGoal(subject, goalId);
        UUID id = UUID.randomUUID();
        accessService.authorize(subject, "milestone:create",
                PlanningAuthorizationResource.forNew("milestone", id, subject.accountId(), subject.tenantId()));
        String fingerprint = fingerprint("milestone:create", goalId.toString(), request.title(), request.criteria(),
                Integer.toString(request.position()));
        return idempotencyService.execute(subject, "MILESTONE_CREATE", id, key, fingerprint,
                PlanningDtos.MilestoneResponse.class, () -> milestoneResponse(milestoneRepository.saveAndFlush(new Milestone(
                        id, goalId, request.title(), request.criteria(), request.position(),
                        subject.accountId(), subject.tenantId()))));
    }

    public List<PlanningDtos.MilestoneResponse> listMilestones(TaskSubject subject, UUID goalId) {
        requireOwnedGoal(subject, goalId);
        accessService.authorize(subject, "milestone:list", PlanningAuthorizationResource.forCollection("milestone", subject.tenantId()));
        return milestoneRepository.findByGoalIdAndOwnerAccountIdAndTenantIdOrderByPositionAscIdAsc(
                        goalId, subject.accountId(), subject.tenantId()).stream().map(PlanningService::milestoneResponse).toList();
    }

    public PlanningDtos.MilestoneResponse getMilestone(TaskSubject subject, UUID id) {
        return milestoneResponse(loadMilestone(subject, id, "milestone:read"));
    }

    public PlanningDtos.MilestoneResponse updateMilestone(
            TaskSubject subject, UUID id, long expectedVersion, PlanningDtos.UpdateMilestoneRequest request, String key) {
        Milestone milestone = loadMilestone(subject, id, "milestone:update");
        if (milestone.getVersion() != expectedVersion) {
            throw new PlanningVersionConflictException();
        }
        String fingerprint = fingerprint("milestone:update", id.toString(), Long.toString(expectedVersion), request.title(),
                request.criteria(), Integer.toString(request.position()));
        return idempotencyService.execute(subject, "MILESTONE_UPDATE", id, key, fingerprint,
                PlanningDtos.MilestoneResponse.class, () -> {
                    milestone.update(request.title(), request.criteria(), request.position());
                    return milestoneResponse(milestoneRepository.saveAndFlush(milestone));
                });
    }

    public PlanningDtos.MilestoneResponse completeMilestone(TaskSubject subject, UUID id, String key) {
        Milestone milestone = loadMilestone(subject, id, "milestone:complete");
        String fingerprint = fingerprint("milestone:complete", id.toString(), Boolean.toString(!milestone.isCompleted()));
        return idempotencyService.execute(subject, "MILESTONE_COMPLETE", id, key, fingerprint,
                PlanningDtos.MilestoneResponse.class, () -> {
                    milestone.toggleCompleted();
                    return milestoneResponse(milestoneRepository.saveAndFlush(milestone));
                });
    }

    private Habit loadHabit(TaskSubject subject, UUID id, String action) {
        Habit habit = habitRepository.findById(id).orElse(null);
        PlanningAuthorizationResource resource = habit == null
                ? PlanningAuthorizationResource.forMissing("habit", id, subject.tenantId())
                : PlanningAuthorizationResource.forExisting("habit", id, habit.getOwnerAccountId(), habit.getTenantId());
        accessService.authorize(subject, action, resource);
        if (habit == null || !subject.accountId().equals(habit.getOwnerAccountId())
                || !subject.tenantId().equals(habit.getTenantId())) {
            throw new PlanningResourceNotFoundException();
        }
        return habit;
    }

    private Routine loadRoutine(TaskSubject subject, UUID id, String action) {
        Routine routine = routineRepository.findById(id).orElse(null);
        PlanningAuthorizationResource resource = routine == null
                ? PlanningAuthorizationResource.forMissing("routine", id, subject.tenantId())
                : PlanningAuthorizationResource.forExisting("routine", id, routine.getOwnerAccountId(), routine.getTenantId());
        accessService.authorize(subject, action, resource);
        if (routine == null || !subject.accountId().equals(routine.getOwnerAccountId())
                || !subject.tenantId().equals(routine.getTenantId())) {
            throw new PlanningResourceNotFoundException();
        }
        return routine;
    }

    private Milestone loadMilestone(TaskSubject subject, UUID id, String action) {
        Milestone milestone = milestoneRepository.findById(id).orElse(null);
        PlanningAuthorizationResource resource = milestone == null
                ? PlanningAuthorizationResource.forMissing("milestone", id, subject.tenantId())
                : PlanningAuthorizationResource.forExisting("milestone", id, milestone.getOwnerAccountId(), milestone.getTenantId());
        accessService.authorize(subject, action, resource);
        if (milestone == null || !subject.accountId().equals(milestone.getOwnerAccountId())
                || !subject.tenantId().equals(milestone.getTenantId())) {
            throw new PlanningResourceNotFoundException();
        }
        return milestone;
    }

    private void requireOwnedGoal(TaskSubject subject, UUID goalId) {
        if (goalRepository.findByIdAndOwnerAccountIdAndTenantId(goalId, subject.accountId(), subject.tenantId()).isEmpty()) {
            throw new PlanningResourceNotFoundException();
        }
    }

    private LocalDate lastOccurrence(Habit habit, TaskSubject subject) {
        LocalDate today = LocalDate.now(ZoneId.of(habit.getTimeZone()));
        return occurrenceRepository.findByHabitIdAndOwnerAccountIdAndTenantIdAndOccurrenceDateBetweenOrderByOccurrenceDateAsc(
                        habit.getId(), subject.accountId(), subject.tenantId(), today.minusDays(MAX_HABIT_TREND_DAYS), today)
                .stream().reduce((first, second) -> second).map(HabitOccurrence::getOccurrenceDate).orElse(null);
    }

    private static int expectedOccurrences(HabitCadence cadence, LocalDate start, LocalDate end) {
        return cadence == HabitCadence.DAILY
                ? (int) (end.toEpochDay() - start.toEpochDay() + 1)
                : (int) ((end.toEpochDay() - start.toEpochDay()) / 7) + 1;
    }

    private static int currentStreak(Habit habit, List<HabitOccurrence> occurrences, LocalDate end) {
        java.util.Set<LocalDate> completed = occurrences.stream().map(HabitOccurrence::getOccurrenceDate).collect(java.util.stream.Collectors.toSet());
        int streak = 0;
        LocalDate cursor = end;
        while (completed.contains(cursor)) {
            streak++;
            cursor = habit.getCadence() == HabitCadence.DAILY ? cursor.minusDays(1) : cursor.minusWeeks(1);
        }
        return streak;
    }

    private static boolean matches(RoutineCadence cadence, LocalDate date, LocalDate anchor) {
        return switch (cadence) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == anchor.getDayOfWeek();
            case MONTHLY -> date.getDayOfMonth() == anchor.getDayOfMonth()
                    || (date.equals(date.with(TemporalAdjusters.lastDayOfMonth()))
                    && anchor.getDayOfMonth() > date.lengthOfMonth());
        };
    }

    private static PlanningDtos.HabitResponse habitResponse(Habit habit, LocalDate lastOccurrenceDate) {
        return new PlanningDtos.HabitResponse(habit.getId(), habit.getName(), habit.getCadence(), habit.getTimeZone(),
                habit.isActive(), habit.getVersion(), lastOccurrenceDate);
    }

    private PlanningDtos.RoutineResponse routineResponse(Routine routine) {
        return new PlanningDtos.RoutineResponse(routine.getId(), routine.getName(), routine.getTimeZone(),
                routine.getCadence(), routine.activities(objectMapper), routine.getVersion());
    }

    private static PlanningDtos.MilestoneResponse milestoneResponse(Milestone milestone) {
        return new PlanningDtos.MilestoneResponse(milestone.getId(), milestone.getGoalId(), milestone.getTitle(),
                milestone.getCriteria(), milestone.getPosition(), milestone.isCompleted(), milestone.getVersion());
    }

    private static String fingerprint(String... values) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "<null>" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

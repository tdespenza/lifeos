package com.lifeos.taskgoal.planning;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.task.idempotency.TaskVersionPrecondition;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public owner-scoped HTTP boundary for habits, routines, and goal milestones. */
@RestController
public class PlanningController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PlanningService service;
    private final TaskAccessService accessService;

    public PlanningController(PlanningService service, TaskAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping("/api/v1/habits")
    public ResponseEntity<PlanningDtos.HabitResponse> createHabit(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody PlanningDtos.CreateHabitRequest request) {
        PlanningDtos.HabitResponse response = service.createHabit(authenticate(authorization), request, key);
        return ResponseEntity.created(URI.create("/api/v1/habits/" + response.id()))
                .eTag(etag(response.version())).body(response);
    }

    @GetMapping("/api/v1/habits")
    public List<PlanningDtos.HabitResponse> listHabits(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return service.listHabits(authenticate(authorization));
    }

    @GetMapping("/api/v1/habits/{habitId}")
    public ResponseEntity<PlanningDtos.HabitResponse> getHabit(
            @PathVariable UUID habitId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        PlanningDtos.HabitResponse response = service.getHabit(authenticate(authorization), habitId);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @PutMapping("/api/v1/habits/{habitId}")
    public ResponseEntity<PlanningDtos.HabitResponse> updateHabit(
            @PathVariable UUID habitId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @RequestHeader(TaskVersionPrecondition.HEADER_NAME) String ifMatch,
            @Valid @RequestBody PlanningDtos.UpdateHabitRequest request) {
        PlanningDtos.HabitResponse response = service.updateHabit(
                authenticate(authorization), habitId, TaskVersionPrecondition.requireSingleHeader(List.of(ifMatch)), request, key);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @PostMapping("/api/v1/habits/{habitId}/occurrences")
    public ResponseEntity<PlanningDtos.HabitResponse> recordOccurrence(
            @PathVariable UUID habitId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody PlanningDtos.RecordHabitOccurrenceRequest request) {
        PlanningDtos.HabitResponse response = service.recordOccurrence(authenticate(authorization), habitId, request, key);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @GetMapping("/api/v1/habits/{habitId}/trend")
    public PlanningDtos.HabitTrendResponse trend(
            @PathVariable UUID habitId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return service.trend(authenticate(authorization), habitId, from, to);
    }

    @PostMapping("/api/v1/routines")
    public ResponseEntity<PlanningDtos.RoutineResponse> createRoutine(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody PlanningDtos.CreateRoutineRequest request) {
        PlanningDtos.RoutineResponse response = service.createRoutine(authenticate(authorization), request, key);
        return ResponseEntity.created(URI.create("/api/v1/routines/" + response.id()))
                .eTag(etag(response.version())).body(response);
    }

    @GetMapping("/api/v1/routines")
    public List<PlanningDtos.RoutineResponse> listRoutines(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return service.listRoutines(authenticate(authorization));
    }

    @GetMapping("/api/v1/routines/{routineId}")
    public ResponseEntity<PlanningDtos.RoutineResponse> getRoutine(
            @PathVariable UUID routineId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        PlanningDtos.RoutineResponse response = service.getRoutine(authenticate(authorization), routineId);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @PutMapping("/api/v1/routines/{routineId}")
    public ResponseEntity<PlanningDtos.RoutineResponse> updateRoutine(
            @PathVariable UUID routineId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @RequestHeader(TaskVersionPrecondition.HEADER_NAME) String ifMatch,
            @Valid @RequestBody PlanningDtos.UpdateRoutineRequest request) {
        PlanningDtos.RoutineResponse response = service.updateRoutine(
                authenticate(authorization), routineId, TaskVersionPrecondition.requireSingleHeader(List.of(ifMatch)), request, key);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @PostMapping("/api/v1/routines/{routineId}/materialize")
    public PlanningDtos.RoutineMaterializationResponse materializeRoutine(
            @PathVariable UUID routineId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return service.materializeRoutine(authenticate(authorization), routineId, from, to, key);
    }

    @PostMapping("/api/v1/goals/{goalId}/milestones")
    public ResponseEntity<PlanningDtos.MilestoneResponse> createMilestone(
            @PathVariable UUID goalId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody PlanningDtos.CreateMilestoneRequest request) {
        PlanningDtos.MilestoneResponse response = service.createMilestone(authenticate(authorization), goalId, request, key);
        return ResponseEntity.created(URI.create("/api/v1/milestones/" + response.id()))
                .eTag(etag(response.version())).body(response);
    }

    @GetMapping("/api/v1/goals/{goalId}/milestones")
    public List<PlanningDtos.MilestoneResponse> listMilestones(
            @PathVariable UUID goalId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return service.listMilestones(authenticate(authorization), goalId);
    }

    @GetMapping("/api/v1/milestones/{milestoneId}")
    public ResponseEntity<PlanningDtos.MilestoneResponse> getMilestone(
            @PathVariable UUID milestoneId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        PlanningDtos.MilestoneResponse response = service.getMilestone(authenticate(authorization), milestoneId);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @PutMapping("/api/v1/milestones/{milestoneId}")
    public ResponseEntity<PlanningDtos.MilestoneResponse> updateMilestone(
            @PathVariable UUID milestoneId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @RequestHeader(TaskVersionPrecondition.HEADER_NAME) String ifMatch,
            @Valid @RequestBody PlanningDtos.UpdateMilestoneRequest request) {
        PlanningDtos.MilestoneResponse response = service.updateMilestone(
                authenticate(authorization), milestoneId, TaskVersionPrecondition.requireSingleHeader(List.of(ifMatch)), request, key);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @PostMapping("/api/v1/milestones/{milestoneId}/complete")
    public ResponseEntity<PlanningDtos.MilestoneResponse> completeMilestone(
            @PathVariable UUID milestoneId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(IDEMPOTENCY_HEADER) String key) {
        PlanningDtos.MilestoneResponse response = service.completeMilestone(authenticate(authorization), milestoneId, key);
        return ResponseEntity.ok().eTag(etag(response.version())).body(response);
    }

    @ExceptionHandler(PlanningResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(PlanningResourceNotFoundException exception) {
        return ResponseEntity.status(404).body(Map.of("error", "The requested planning resource was not found"));
    }

    @ExceptionHandler(PlanningVersionConflictException.class)
    public ResponseEntity<Map<String, String>> staleVersion(PlanningVersionConflictException exception) {
        return ResponseEntity.status(412).body(Map.of("error", "The planning representation is no longer current"));
    }

    @ExceptionHandler(PlanningIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(PlanningIdempotencyConflictException exception) {
        return ResponseEntity.status(409).body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler(PlanningIdempotencyUnavailableException.class)
    public ResponseEntity<Map<String, String>> idempotencyUnavailable(PlanningIdempotencyUnavailableException exception) {
        return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Planning idempotency is temporarily unavailable"));
    }

    @ExceptionHandler(InvalidPlanningIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotency(InvalidPlanningIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    private TaskSubject authenticate(String authorization) {
        return accessService.authenticate(authorization);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

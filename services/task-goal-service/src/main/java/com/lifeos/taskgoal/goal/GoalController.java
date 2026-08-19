package com.lifeos.taskgoal.goal;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.algorithm.CyclicDependencyException;
import com.lifeos.taskgoal.goal.algorithm.InvalidDependencyGraphException;
import com.lifeos.taskgoal.goal.dto.CreateGoalRequest;
import com.lifeos.taskgoal.goal.dto.DependencyOrderRequest;
import com.lifeos.taskgoal.goal.dto.DependencyOrderResponse;
import com.lifeos.taskgoal.goal.dto.GoalResponse;
import com.lifeos.taskgoal.goal.dto.UpdateGoalRequest;
import com.lifeos.taskgoal.goal.idempotency.GoalIdempotencyConflictException;
import com.lifeos.taskgoal.goal.idempotency.GoalIdempotencyKey;
import com.lifeos.taskgoal.goal.idempotency.GoalIdempotencyUnavailableException;
import com.lifeos.taskgoal.goal.idempotency.GoalVersionPrecondition;
import com.lifeos.taskgoal.goal.idempotency.GoalVersionPreconditionRequiredException;
import com.lifeos.taskgoal.goal.idempotency.InvalidGoalVersionPreconditionException;
import com.lifeos.taskgoal.goal.idempotency.InvalidGoalIdempotencyKeyException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoalController {

    private final GoalService service;
    private final TaskAccessService accessService;

    public GoalController(GoalService service, TaskAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping("/api/v1/goals")
    public ResponseEntity<GoalResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = GoalIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody CreateGoalRequest request) {
        TaskSubject subject = authenticate(authorizationHeader);
        String idempotencyKey = GoalIdempotencyKey.requireSingleHeader(idempotencyKeys);
        GoalResponse body = GoalResponse.from(request.priority() == null && request.dueAt() == null
                ? service.create(subject, request.title(), idempotencyKey)
                : service.create(subject, request.title(), request.priority(), request.dueAt(), idempotencyKey));
        return ResponseEntity.created(URI.create("/api/v1/goals/" + body.id()))
                .eTag(etag(body.version()))
                .body(body);
    }

    @GetMapping("/api/v1/goals")
    public List<GoalResponse> listAll(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        return service.listAll(authenticate(authorizationHeader)).stream().map(GoalResponse::from).toList();
    }

    @GetMapping("/api/v1/goals/{goalId}")
    public ResponseEntity<GoalResponse> get(
            @PathVariable UUID goalId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        GoalResponse body = GoalResponse.from(service.get(authenticate(authorizationHeader), goalId));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    /** Replaces the currently mutable goal title under strong optimistic concurrency control. */
    @PutMapping("/api/v1/goals/{goalId}")
    public ResponseEntity<GoalResponse> update(
            @PathVariable UUID goalId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = GoalIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = GoalVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatchValues,
            @Valid @RequestBody UpdateGoalRequest request) {
        TaskSubject subject = authenticate(authorizationHeader);
        long expectedVersion = GoalVersionPrecondition.requireSingleHeader(ifMatchValues);
        String idempotencyKey = GoalIdempotencyKey.requireSingleHeader(idempotencyKeys);
        GoalResponse body = GoalResponse.from(request.priority() == null && request.dueAt() == null
                ? service.update(subject, goalId, expectedVersion, request.title(), idempotencyKey)
                : service.update(subject, goalId, expectedVersion, request.title(), request.priority(), request.dueAt(), idempotencyKey));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    /** Completes an active goal under strong optimistic concurrency control. */
    @PostMapping("/api/v1/goals/{goalId}/complete")
    public ResponseEntity<GoalResponse> complete(
            @PathVariable UUID goalId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = GoalIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = GoalVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatchValues) {
        GoalResponse body = GoalResponse.from(service.complete(
                authenticate(authorizationHeader),
                goalId,
                GoalVersionPrecondition.requireSingleHeader(ifMatchValues),
                GoalIdempotencyKey.requireSingleHeader(idempotencyKeys)));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    /** Archives an active or completed goal under strong optimistic concurrency control. */
    @PostMapping("/api/v1/goals/{goalId}/archive")
    public ResponseEntity<GoalResponse> archive(
            @PathVariable UUID goalId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = GoalIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = GoalVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatchValues) {
        GoalResponse body = GoalResponse.from(service.archive(
                authenticate(authorizationHeader),
                goalId,
                GoalVersionPrecondition.requireSingleHeader(ifMatchValues),
                GoalIdempotencyKey.requireSingleHeader(idempotencyKeys)));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PostMapping("/api/v1/goals/dependency-order")
    public DependencyOrderResponse dependencyOrder(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody DependencyOrderRequest request) {
        List<String> order = service.resolveDependencyOrder(
                authenticate(authorizationHeader),
                request.goals(),
                request.dependencies() == null ? List.of() : request.dependencies());
        return new DependencyOrderResponse(order);
    }

    @ExceptionHandler(CyclicDependencyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleCycle(CyclicDependencyException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(InvalidDependencyGraphException.class)
    public ResponseEntity<Map<String, String>> invalidDependencyGraph(InvalidDependencyGraphException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Dependency graph input is invalid"));
    }

    @ExceptionHandler(InvalidGoalIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotencyKey(InvalidGoalIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    @ExceptionHandler(GoalIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(GoalIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler(GoalIdempotencyUnavailableException.class)
    public ResponseEntity<Map<String, String>> idempotencyUnavailable(GoalIdempotencyUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Idempotency request is temporarily unavailable"));
    }

    @ExceptionHandler(GoalVersionPreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingVersionPrecondition(
            GoalVersionPreconditionRequiredException exception) {
        return ResponseEntity.status(428)
                .body(Map.of("error", "If-Match is required for goal lifecycle mutations"));
    }

    @ExceptionHandler(InvalidGoalVersionPreconditionException.class)
    public ResponseEntity<Map<String, String>> invalidVersionPrecondition(
            InvalidGoalVersionPreconditionException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "A valid strong If-Match goal version is required"));
    }

    @ExceptionHandler(GoalVersionConflictException.class)
    public ResponseEntity<Map<String, String>> staleVersion(GoalVersionConflictException exception) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("error", "Goal representation is no longer current"));
    }

    @ExceptionHandler(GoalLifecycleTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidLifecycleTransition(GoalLifecycleTransitionException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Goal lifecycle transition is not valid"));
    }

    private TaskSubject authenticate(String authorizationHeader) {
        return accessService.authenticate(authorizationHeader);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

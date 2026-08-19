package com.lifeos.taskgoal.task;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.task.dto.CreateTaskRequest;
import com.lifeos.taskgoal.task.dto.TaskResponse;
import com.lifeos.taskgoal.task.dto.UpdateTaskRequest;
import com.lifeos.taskgoal.task.idempotency.InvalidTaskIdempotencyKeyException;
import com.lifeos.taskgoal.task.idempotency.InvalidTaskVersionPreconditionException;
import com.lifeos.taskgoal.task.idempotency.TaskIdempotencyConflictException;
import com.lifeos.taskgoal.task.idempotency.TaskIdempotencyKey;
import com.lifeos.taskgoal.task.idempotency.TaskIdempotencyUnavailableException;
import com.lifeos.taskgoal.task.idempotency.TaskVersionPrecondition;
import com.lifeos.taskgoal.task.idempotency.TaskVersionPreconditionRequiredException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for the owner-scoped versioned Task lifecycle. */
@RestController
public class TaskController {

    private final TaskService service;
    private final TaskAccessService accessService;

    public TaskController(TaskService service, TaskAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping("/api/v1/tasks")
    public ResponseEntity<TaskResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = TaskIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody CreateTaskRequest request) {
        TaskSubject subject = authenticate(authorizationHeader);
        String idempotencyKey = TaskIdempotencyKey.requireSingleHeader(idempotencyKeys);
        TaskResponse body = TaskResponse.from(request.priority() == null && request.dueAt() == null
                ? service.create(subject, request.title(), idempotencyKey)
                : service.create(subject, request.title(), request.priority(), request.dueAt(), idempotencyKey));
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + body.id()))
                .eTag(etag(body.version()))
                .body(body);
    }

    @GetMapping("/api/v1/tasks")
    public List<TaskResponse> listAll(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        return service.listAll(authenticate(authorizationHeader)).stream().map(TaskResponse::from).toList();
    }

    @GetMapping("/api/v1/tasks/{taskId}")
    public ResponseEntity<TaskResponse> get(
            @PathVariable UUID taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        TaskResponse body = TaskResponse.from(service.get(authenticate(authorizationHeader), taskId));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/tasks/{taskId}")
    public ResponseEntity<TaskResponse> update(
            @PathVariable UUID taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = TaskIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = TaskVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatchValues,
            @Valid @RequestBody UpdateTaskRequest request) {
        TaskSubject subject = authenticate(authorizationHeader);
        long expectedVersion = TaskVersionPrecondition.requireSingleHeader(ifMatchValues);
        String idempotencyKey = TaskIdempotencyKey.requireSingleHeader(idempotencyKeys);
        TaskResponse body = TaskResponse.from(request.priority() == null && request.dueAt() == null
                ? service.update(subject, taskId, expectedVersion, request.title(), idempotencyKey)
                : service.update(subject, taskId, expectedVersion, request.title(), request.priority(), request.dueAt(), idempotencyKey));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PostMapping("/api/v1/tasks/{taskId}/complete")
    public ResponseEntity<TaskResponse> complete(
            @PathVariable UUID taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = TaskIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = TaskVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatchValues) {
        TaskResponse body = TaskResponse.from(service.complete(
                authenticate(authorizationHeader),
                taskId,
                TaskVersionPrecondition.requireSingleHeader(ifMatchValues),
                TaskIdempotencyKey.requireSingleHeader(idempotencyKeys)));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PostMapping("/api/v1/tasks/{taskId}/cancel")
    public ResponseEntity<TaskResponse> cancel(
            @PathVariable UUID taskId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = TaskIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = TaskVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatchValues) {
        TaskResponse body = TaskResponse.from(service.cancel(
                authenticate(authorizationHeader),
                taskId,
                TaskVersionPrecondition.requireSingleHeader(ifMatchValues),
                TaskIdempotencyKey.requireSingleHeader(idempotencyKeys)));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @ExceptionHandler(InvalidTaskIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotencyKey(InvalidTaskIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    @ExceptionHandler(TaskIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(TaskIdempotencyConflictException exception) {
        return ResponseEntity.status(409).body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler(TaskIdempotencyUnavailableException.class)
    public ResponseEntity<Map<String, String>> idempotencyUnavailable(TaskIdempotencyUnavailableException exception) {
        return ResponseEntity.status(503)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Idempotency request is temporarily unavailable"));
    }

    @ExceptionHandler(TaskVersionPreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingVersionPrecondition(
            TaskVersionPreconditionRequiredException exception) {
        return ResponseEntity.status(428)
                .body(Map.of("error", "If-Match is required for task lifecycle mutations"));
    }

    @ExceptionHandler(InvalidTaskVersionPreconditionException.class)
    public ResponseEntity<Map<String, String>> invalidVersionPrecondition(
            InvalidTaskVersionPreconditionException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "A valid strong If-Match task version is required"));
    }

    @ExceptionHandler(TaskVersionConflictException.class)
    public ResponseEntity<Map<String, String>> staleVersion(TaskVersionConflictException exception) {
        return ResponseEntity.status(412).body(Map.of("error", "Task representation is no longer current"));
    }

    @ExceptionHandler(TaskLifecycleTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidLifecycleTransition(TaskLifecycleTransitionException exception) {
        return ResponseEntity.status(409).body(Map.of("error", "Task lifecycle transition is not valid"));
    }

    private TaskSubject authenticate(String authorizationHeader) {
        return accessService.authenticate(authorizationHeader);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

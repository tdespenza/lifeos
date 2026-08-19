package com.lifeos.taskgoal.dependency;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.dependency.dto.PersistedDependencyNodeResponse;
import com.lifeos.taskgoal.dependency.dto.PersistedDependencyOrderResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for durable Task/Goal edges and their complete persisted execution order. */
@RestController
public class PersistedDependencyController {

    private final PersistedDependencyService service;
    private final TaskAccessService accessService;

    public PersistedDependencyController(PersistedDependencyService service, TaskAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    /**
     * Stores {@code predecessor -> dependent}. Repeating the same request is an idempotent no-op.
     */
    @PutMapping("/api/v1/dependencies/{dependentType}/{dependentId}/depends-on/{predecessorType}/{predecessorId}")
    public ResponseEntity<Void> add(
            @PathVariable DependencyNodeType dependentType,
            @PathVariable UUID dependentId,
            @PathVariable DependencyNodeType predecessorType,
            @PathVariable UUID predecessorId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        service.add(
                authenticate(authorizationHeader),
                new PersistedDependencyNode(predecessorType, predecessorId),
                new PersistedDependencyNode(dependentType, dependentId));
        return ResponseEntity.noContent().build();
    }

    /** Removes {@code predecessor -> dependent}; repeating deletion is an idempotent no-op. */
    @DeleteMapping("/api/v1/dependencies/{dependentType}/{dependentId}/depends-on/{predecessorType}/{predecessorId}")
    public ResponseEntity<Void> remove(
            @PathVariable DependencyNodeType dependentType,
            @PathVariable UUID dependentId,
            @PathVariable DependencyNodeType predecessorType,
            @PathVariable UUID predecessorId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        service.remove(
                authenticate(authorizationHeader),
                new PersistedDependencyNode(predecessorType, predecessorId),
                new PersistedDependencyNode(dependentType, dependentId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/dependencies/execution-order")
    public PersistedDependencyOrderResponse order(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        List<PersistedDependencyNodeResponse> order = service.order(authenticate(authorizationHeader)).stream()
                .map(PersistedDependencyNodeResponse::from)
                .toList();
        return new PersistedDependencyOrderResponse(order);
    }

    @ExceptionHandler(SelfDependencyException.class)
    public ResponseEntity<Map<String, String>> selfDependency(SelfDependencyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A dependency cannot reference the same node"));
    }

    @ExceptionHandler(DependencyCycleException.class)
    public ResponseEntity<Map<String, String>> cycle(DependencyCycleException exception) {
        return ResponseEntity.status(409).body(Map.of("error", "Dependency would create a cycle"));
    }

    @ExceptionHandler(DependencyGraphTooLargeException.class)
    public ResponseEntity<Map<String, String>> graphTooLarge(DependencyGraphTooLargeException exception) {
        return ResponseEntity.status(413).body(Map.of("error", "Dependency graph exceeds the configured limit"));
    }

    @ExceptionHandler(DependencyPersistenceUnavailableException.class)
    public ResponseEntity<Map<String, String>> persistenceUnavailable(DependencyPersistenceUnavailableException exception) {
        return ResponseEntity.status(503)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Dependency persistence is temporarily unavailable"));
    }

    private TaskSubject authenticate(String authorizationHeader) {
        return accessService.authenticate(authorizationHeader);
    }
}

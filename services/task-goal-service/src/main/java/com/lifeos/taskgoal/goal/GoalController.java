package com.lifeos.taskgoal.goal;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.algorithm.CyclicDependencyException;
import com.lifeos.taskgoal.goal.dto.CreateGoalRequest;
import com.lifeos.taskgoal.goal.dto.DependencyOrderRequest;
import com.lifeos.taskgoal.goal.dto.DependencyOrderResponse;
import com.lifeos.taskgoal.goal.dto.GoalResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
            @Valid @RequestBody CreateGoalRequest request) {
        GoalResponse body = GoalResponse.from(service.create(authenticate(authorizationHeader), request.title()));
        return ResponseEntity.created(URI.create("/api/v1/goals/" + body.id())).body(body);
    }

    @GetMapping("/api/v1/goals")
    public List<GoalResponse> listAll(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        return service.listAll(authenticate(authorizationHeader)).stream().map(GoalResponse::from).toList();
    }

    @GetMapping("/api/v1/goals/{goalId}")
    public GoalResponse get(
            @PathVariable UUID goalId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        return GoalResponse.from(service.get(authenticate(authorizationHeader), goalId));
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

    private TaskSubject authenticate(String authorizationHeader) {
        return accessService.authenticate(authorizationHeader);
    }
}

package com.lifeos.taskgoal.goal;

import com.lifeos.taskgoal.goal.algorithm.CyclicDependencyException;
import com.lifeos.taskgoal.goal.dto.CreateGoalRequest;
import com.lifeos.taskgoal.goal.dto.DependencyOrderRequest;
import com.lifeos.taskgoal.goal.dto.DependencyOrderResponse;
import com.lifeos.taskgoal.goal.dto.GoalResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoalController {

    private final GoalService service;

    public GoalController(GoalService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/goals")
    public ResponseEntity<GoalResponse> create(@Valid @RequestBody CreateGoalRequest request) {
        GoalResponse body = GoalResponse.from(service.create(request.title()));
        return ResponseEntity.created(URI.create("/api/v1/goals/" + body.id())).body(body);
    }

    @GetMapping("/api/v1/goals")
    public List<GoalResponse> listAll() {
        return service.listAll().stream().map(GoalResponse::from).toList();
    }

    @PostMapping("/api/v1/goals/dependency-order")
    public DependencyOrderResponse dependencyOrder(@Valid @RequestBody DependencyOrderRequest request) {
        List<String> order = service.resolveDependencyOrder(
                request.goals(), request.dependencies() == null ? List.of() : request.dependencies());
        return new DependencyOrderResponse(order);
    }

    @ExceptionHandler(CyclicDependencyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleCycle(CyclicDependencyException ex) {
        return ex.getMessage();
    }
}

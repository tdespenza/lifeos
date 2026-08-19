package com.lifeos.calendar.api;

import com.lifeos.calendar.authorization.CalendarAccessService;
import com.lifeos.calendar.authorization.CalendarAuthorizationActions;
import com.lifeos.calendar.authorization.CalendarAuthorizationResource;
import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.service.CalendarConflictDetector;
import com.lifeos.calendar.service.CalendarOptimizationService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped conflict and non-mutating local optimization endpoints. */
@RestController
public class CalendarSchedulingController {

    private final CalendarAccessService accessService;
    private final CalendarConflictDetector conflictDetector;
    private final CalendarOptimizationService optimizationService;

    public CalendarSchedulingController(
            CalendarAccessService accessService,
            CalendarConflictDetector conflictDetector,
            CalendarOptimizationService optimizationService) {
        this.accessService = accessService;
        this.conflictDetector = conflictDetector;
        this.optimizationService = optimizationService;
    }

    @GetMapping("/api/v1/calendar/conflicts")
    public List<CalendarConflictResponse> conflicts(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        CalendarSubject subject = accessService.authenticate(authorization);
        accessService.authorize(
                subject, CalendarAuthorizationActions.CONFLICT_READ, CalendarAuthorizationResource.collection(subject));
        return conflictDetector.detect(subject.tenantId(), subject.accountId(), from, to).stream()
                .map(CalendarConflictResponse::from)
                .toList();
    }

    @PostMapping("/api/v1/calendar/optimization-suggestions")
    public CalendarOptimizationResponse optimize(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CalendarOptimizationRequest request) {
        return optimizationService.suggest(accessService.authenticate(authorization), request);
    }
}

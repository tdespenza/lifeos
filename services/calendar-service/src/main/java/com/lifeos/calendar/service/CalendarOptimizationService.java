package com.lifeos.calendar.service;

import com.lifeos.calendar.api.CalendarOptimizationRequest;
import com.lifeos.calendar.api.CalendarOptimizationResponse;
import com.lifeos.calendar.api.CalendarOptimizationSuggestion;
import com.lifeos.calendar.authorization.CalendarAccessService;
import com.lifeos.calendar.authorization.CalendarAuthorizationActions;
import com.lifeos.calendar.authorization.CalendarAuthorizationResource;
import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.authorization.TaskGoalOwnershipProjection;
import com.lifeos.calendar.api.CalendarPlanningCandidateRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Local deterministic scheduling suggestions. It never mutates Calendar and only ranks Task/Goal
 * candidates after the reauthorized planning projection returns bounded priority/deadline facts.
 */
@Service
public class CalendarOptimizationService {

    private final CalendarAccessService accessService;
    private final CalendarConflictDetector conflictDetector;
    private final CalendarSchedulingMetrics metrics;
    private final TaskGoalOwnershipProjection taskGoalProjection;

    @Autowired
    public CalendarOptimizationService(
            CalendarAccessService accessService,
            CalendarConflictDetector conflictDetector,
            CalendarSchedulingMetrics metrics,
            TaskGoalOwnershipProjection taskGoalProjection) {
        this.accessService = accessService;
        this.conflictDetector = conflictDetector;
        this.metrics = metrics;
        this.taskGoalProjection = taskGoalProjection;
    }

    /** Compatibility constructor for local-only callers; candidate ranking requires the projection. */
    CalendarOptimizationService(
            CalendarAccessService accessService,
            CalendarConflictDetector conflictDetector,
            CalendarSchedulingMetrics metrics) {
        this(accessService, conflictDetector, metrics, null);
    }

    /** Suggests the earliest bounded free windows in deterministic order. */
    public CalendarOptimizationResponse suggest(CalendarSubject subject, CalendarOptimizationRequest request) {
        accessService.authorize(subject, CalendarAuthorizationActions.OPTIMIZE, CalendarAuthorizationResource.collection(subject));
        if (!request.to().isAfter(request.from())) {
            throw new IllegalArgumentException("optimization range must have from before to");
        }
        Duration minimum = Duration.ofMinutes(request.minimumFocusMinutes());
        List<CalendarConflict> commitments = new ArrayList<>(conflictDetector.detect(
                subject.tenantId(), subject.accountId(), request.from(), request.to()));
        commitments.sort(Comparator.comparing(CalendarConflict::startAt).thenComparing(CalendarConflict::sourceId));
        List<FreeWindow> freeWindows = freeWindows(request.from(), request.to(), commitments);
        if (request.candidates() == null || request.candidates().isEmpty()) {
            metrics.recordOptimizationDegraded();
            return new CalendarOptimizationResponse(
                    freeWindows.stream()
                            .filter(window -> window.duration().compareTo(minimum) >= 0)
                            .limit(request.maxSuggestions())
                            .map(window -> new CalendarOptimizationSuggestion(
                                    "Protect this free interval for focus time.",
                                    window.startAt(),
                                    window.endAt(),
                                    window.timeZone()))
                            .toList(),
                    List.of("task-goal"));
        }
        if (taskGoalProjection == null) {
            metrics.recordOptimizationDegraded();
            return fallback(request, freeWindows);
        }
        List<RankedCandidate> ranked = new ArrayList<>();
        try {
            for (CalendarPlanningCandidateRequest candidate : request.candidates()) {
                TaskGoalOwnershipProjection.TaskGoalPlanningFacts facts = taskGoalProjection.project(
                        subject, candidate.linkType(), candidate.resourceId());
                if (facts == null) {
                    throw new UnsupportedCalendarLinkException();
                }
                ranked.add(new RankedCandidate(candidate, facts));
            }
        } catch (RuntimeException unavailable) {
            metrics.recordOptimizationDegraded();
            return fallback(request, freeWindows);
        }
        ranked.sort(Comparator
                .comparingInt((RankedCandidate candidate) -> candidate.facts().priority())
                .thenComparing(candidate -> candidate.facts().dueAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.candidate().resourceId()));
        List<CalendarOptimizationSuggestion> suggestions = new ArrayList<>();
        int windowIndex = 0;
        Instant cursor = null;
        FreeWindow current = null;
        for (RankedCandidate candidate : ranked) {
            Duration requested = Duration.ofMinutes(candidate.candidate().focusMinutes());
            while (windowIndex < freeWindows.size()) {
                FreeWindow candidateWindow = freeWindows.get(windowIndex);
                Instant start = cursor == null ? candidateWindow.startAt() : cursor;
                if (candidateWindow.endAt().isAfter(start)
                        && Duration.between(start, candidateWindow.endAt()).compareTo(requested) >= 0) {
                    current = candidateWindow;
                    cursor = start;
                    break;
                }
                windowIndex++;
                cursor = null;
            }
            if (current == null || cursor == null) {
                break;
            }
            Instant end = cursor.plus(requested);
            String due = candidate.facts().dueAt() == null ? "no deadline" : "due " + candidate.facts().dueAt();
            suggestions.add(new CalendarOptimizationSuggestion(
                    "Priority " + candidate.facts().priority() + " " + candidate.candidate().linkType()
                            + " " + candidate.candidate().resourceId() + " (" + due + ")",
                    cursor,
                    end,
                    current.timeZone()));
            cursor = end;
            if (suggestions.size() == request.maxSuggestions()) {
                break;
            }
        }
        return new CalendarOptimizationResponse(List.copyOf(suggestions), List.of());
    }

    private CalendarOptimizationResponse fallback(CalendarOptimizationRequest request, List<FreeWindow> freeWindows) {
        Duration minimum = Duration.ofMinutes(request.minimumFocusMinutes());
        return new CalendarOptimizationResponse(
                freeWindows.stream()
                        .filter(window -> window.duration().compareTo(minimum) >= 0)
                        .limit(request.maxSuggestions())
                        .map(window -> new CalendarOptimizationSuggestion(
                                "Protect this free interval for focus time.",
                                window.startAt(),
                                window.endAt(),
                                window.timeZone()))
                        .toList(),
                List.of("task-goal"));
    }

    private static List<FreeWindow> freeWindows(
            Instant from, Instant to, List<CalendarConflict> commitments) {
        List<FreeWindow> windows = new ArrayList<>();
        Instant cursor = from;
        for (CalendarConflict commitment : commitments) {
            if (commitment.startAt().isAfter(cursor)
                    && commitment.startAt().isBefore(to)) {
                windows.add(new FreeWindow(cursor, commitment.startAt(), commitment.timeZone()));
            }
            if (commitment.endAt().isAfter(cursor)) {
                cursor = commitment.endAt();
            }
        }
        if (to.isAfter(cursor)) {
            windows.add(new FreeWindow(cursor, to, "UTC"));
        }
        return List.copyOf(windows);
    }

    private record FreeWindow(Instant startAt, Instant endAt, String timeZone) {
        Duration duration() {
            return Duration.between(startAt, endAt);
        }
    }

    private record RankedCandidate(
            CalendarPlanningCandidateRequest candidate,
            TaskGoalOwnershipProjection.TaskGoalPlanningFacts facts) {
        RankedCandidate {
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(facts);
        }
    }
}

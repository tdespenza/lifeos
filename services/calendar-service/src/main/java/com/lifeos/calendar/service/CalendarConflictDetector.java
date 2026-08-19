package com.lifeos.calendar.service;

import com.lifeos.calendar.domain.CalendarOccurrenceRepository;
import com.lifeos.calendar.domain.CalendarOccurrenceStatus;
import com.lifeos.calendar.domain.CalendarTimeBlockRepository;
import com.lifeos.calendar.domain.CalendarTimeBlockStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Indexed half-open interval detector with a deliberately bounded caller-owned window. */
@Service
public class CalendarConflictDetector {

    private static final Duration MAX_WINDOW = Duration.ofDays(90);
    private static final int MAX_CONFLICTS = 500;

    private final CalendarOccurrenceRepository occurrenceRepository;
    private final CalendarTimeBlockRepository timeBlockRepository;

    public CalendarConflictDetector(
            CalendarOccurrenceRepository occurrenceRepository, CalendarTimeBlockRepository timeBlockRepository) {
        this.occurrenceRepository = occurrenceRepository;
        this.timeBlockRepository = timeBlockRepository;
    }

    /** Returns all active owner-scoped overlapping commitments in deterministic order. */
    public List<CalendarConflict> detect(String tenantId, UUID ownerAccountId, Instant startAt, Instant endAt) {
        validateWindow(startAt, endAt);
        List<CalendarConflict> conflicts = new ArrayList<>();
        occurrenceRepository
                .findActiveOverlapping(
                        tenantId,
                        ownerAccountId,
                        CalendarOccurrenceStatus.ACTIVE,
                        startAt,
                        endAt,
                        PageRequest.of(0, MAX_CONFLICTS + 1))
                .forEach(occurrence -> conflicts.add(new CalendarConflict(
                        "EVENT",
                        occurrence.getId(),
                        occurrence.getStartAt(),
                        occurrence.getEndAt(),
                        occurrence.getTimeZone())));
        timeBlockRepository
                .findActiveOverlapping(
                        tenantId,
                        ownerAccountId,
                        CalendarTimeBlockStatus.ACTIVE,
                        startAt,
                        endAt,
                        null,
                        PageRequest.of(0, MAX_CONFLICTS + 1))
                .forEach(block -> conflicts.add(new CalendarConflict(
                        "TIME_BLOCK", block.getId(), block.getStartAt(), block.getEndAt(), block.getTimeZone())));
        return boundedAndSorted(conflicts);
    }

    /** Returns any blocking event occurrence or other time block, excluding the block being replaced. */
    public List<CalendarConflict> detectForTimeBlock(
            String tenantId, UUID ownerAccountId, UUID excludedBlockId, Instant startAt, Instant endAt) {
        validateWindow(startAt, endAt);
        List<CalendarConflict> conflicts = new ArrayList<>();
        occurrenceRepository
                .findActiveOverlapping(
                        tenantId,
                        ownerAccountId,
                        CalendarOccurrenceStatus.ACTIVE,
                        startAt,
                        endAt,
                        PageRequest.of(0, MAX_CONFLICTS + 1))
                .forEach(occurrence -> conflicts.add(new CalendarConflict(
                        "EVENT",
                        occurrence.getId(),
                        occurrence.getStartAt(),
                        occurrence.getEndAt(),
                        occurrence.getTimeZone())));
        timeBlockRepository
                .findActiveOverlapping(
                        tenantId,
                        ownerAccountId,
                        CalendarTimeBlockStatus.ACTIVE,
                        startAt,
                        endAt,
                        excludedBlockId,
                        PageRequest.of(0, MAX_CONFLICTS + 1))
                .forEach(block -> conflicts.add(new CalendarConflict(
                        "TIME_BLOCK", block.getId(), block.getStartAt(), block.getEndAt(), block.getTimeZone())));
        return boundedAndSorted(conflicts);
    }

    private static List<CalendarConflict> boundedAndSorted(List<CalendarConflict> conflicts) {
        if (conflicts.size() > MAX_CONFLICTS) {
            throw new CalendarConflictResultTooLargeException();
        }
        conflicts.sort(Comparator.comparing(CalendarConflict::startAt)
                .thenComparing(CalendarConflict::endAt)
                .thenComparing(CalendarConflict::sourceType)
                .thenComparing(CalendarConflict::sourceId));
        return List.copyOf(conflicts);
    }

    private static void validateWindow(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("calendar window must have startAt before endAt");
        }
        if (Duration.between(startAt, endAt).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("calendar window must not exceed 90 days");
        }
    }
}

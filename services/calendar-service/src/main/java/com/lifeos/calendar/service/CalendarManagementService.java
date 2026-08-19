package com.lifeos.calendar.service;

import com.lifeos.calendar.api.CalendarEventResponse;
import com.lifeos.calendar.api.CalendarRecurrence;
import com.lifeos.calendar.api.CalendarReminderRequest;
import com.lifeos.calendar.api.CalendarTimeBlockResponse;
import com.lifeos.calendar.api.CreateCalendarEventRequest;
import com.lifeos.calendar.api.CreateCalendarTimeBlockRequest;
import com.lifeos.calendar.api.UpdateCalendarEventRequest;
import com.lifeos.calendar.api.UpdateCalendarTimeBlockRequest;
import com.lifeos.calendar.audit.CalendarSecurityAuditService;
import com.lifeos.calendar.authorization.CalendarAccessService;
import com.lifeos.calendar.authorization.CalendarAuthorizationActions;
import com.lifeos.calendar.authorization.CalendarAuthorizationResource;
import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.authorization.TaskGoalOwnershipProjection;
import com.lifeos.calendar.domain.CalendarAuditOutcome;
import com.lifeos.calendar.domain.CalendarEvent;
import com.lifeos.calendar.domain.CalendarEventReminder;
import com.lifeos.calendar.domain.CalendarEventReminderRepository;
import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.calendar.domain.CalendarOutboxEventRepository;
import com.lifeos.calendar.domain.CalendarOccurrenceRepository;
import com.lifeos.calendar.domain.CalendarReminderRepository;
import com.lifeos.calendar.domain.CalendarReminderState;
import com.lifeos.calendar.domain.CalendarTimeBlock;
import com.lifeos.calendar.domain.CalendarTimeBlockRepository;
import com.lifeos.calendar.domain.CalendarTimeBlockStatus;
import com.lifeos.calendar.idempotency.CalendarIdempotencyResult;
import com.lifeos.calendar.idempotency.CalendarMutationIdempotencyService;
import com.lifeos.calendar.idempotency.CalendarMutationOperation;
import com.lifeos.calendar.reminder.CalendarOccurrenceFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-scoped event and time-block lifecycle service with durable retry-safe mutations. */
@Service
public class CalendarManagementService {

    private final CalendarAccessService accessService;
    private final CalendarEventRepository eventRepository;
    private final CalendarEventReminderRepository eventReminderRepository;
    private final CalendarTimeBlockRepository timeBlockRepository;
    private final CalendarOccurrenceFactory occurrenceFactory;
    private final CalendarReminderRepository reminderRepository;
    private final CalendarOccurrenceRepository occurrenceRepository;
    private final CalendarOutboxEventRepository outboxRepository;
    private final CalendarScheduleLockService scheduleLockService;
    private final CalendarConflictDetector conflictDetector;
    private final CalendarMutationIdempotencyService idempotencyService;
    private final CalendarSecurityAuditService auditService;
    private final TaskGoalOwnershipProjection taskGoalProjection;
    private final Clock clock;

    public CalendarManagementService(
            CalendarAccessService accessService,
            CalendarEventRepository eventRepository,
            CalendarEventReminderRepository eventReminderRepository,
            CalendarTimeBlockRepository timeBlockRepository,
            CalendarOccurrenceFactory occurrenceFactory,
            CalendarReminderRepository reminderRepository,
            CalendarOccurrenceRepository occurrenceRepository,
            CalendarOutboxEventRepository outboxRepository,
            CalendarScheduleLockService scheduleLockService,
            CalendarConflictDetector conflictDetector,
            CalendarMutationIdempotencyService idempotencyService,
            CalendarSecurityAuditService auditService,
            TaskGoalOwnershipProjection taskGoalProjection,
            Clock clock) {
        this.accessService = accessService;
        this.eventRepository = eventRepository;
        this.eventReminderRepository = eventReminderRepository;
        this.timeBlockRepository = timeBlockRepository;
        this.occurrenceFactory = occurrenceFactory;
        this.reminderRepository = reminderRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.outboxRepository = outboxRepository;
        this.scheduleLockService = scheduleLockService;
        this.conflictDetector = conflictDetector;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.taskGoalProjection = taskGoalProjection;
        this.clock = clock;
    }

    public CalendarIdempotencyResult<CalendarEventResponse> createEvent(
            CalendarSubject subject, CreateCalendarEventRequest request, String idempotencyKey) {
        UUID eventId = UUID.randomUUID();
        accessService.authorize(
                subject, CalendarAuthorizationActions.EVENT_CREATE, CalendarAuthorizationResource.newEvent(eventId, subject));
        validateReminders(request.reminders());
        return idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                CalendarMutationOperation.EVENT_CREATE,
                "event:create",
                idempotencyKey,
                request,
                null,
                CalendarEventResponse.class,
                201,
                "/api/v1/calendar/events/" + eventId,
                () -> {
                    Instant now = clock.instant();
                    CalendarEvent event = eventRepository.save(CalendarEvent.active(
                            eventId,
                            subject.accountId(),
                            subject.tenantId(),
                            request.title(),
                            request.description(),
                            request.startAt(),
                            request.endAt(),
                            request.timeZone(),
                            CalendarRecurrence.toStored(request.recurrence()),
                            currentCorrelationId(),
                            now));
                    replaceReminderTemplates(event.getId(), request.reminders(), now);
                    occurrenceFactory.create(event, event.getRecurrenceRevision(), event.getStartAt(), event.getEndAt(), now);
                    eventRepository.flush();
                    CalendarEventResponse response = eventResponse(event);
                    auditService.record(subject, "calendar.event.create", CalendarAuditOutcome.SUCCESS, "calendar-event", eventId, null);
                    return response;
                });
    }

    @Transactional(readOnly = true)
    public List<CalendarEventResponse> listEvents(CalendarSubject subject, int limit) {
        accessService.authorize(subject, CalendarAuthorizationActions.EVENT_LIST, CalendarAuthorizationResource.collection(subject));
        return eventRepository.findByTenantIdAndOwnerAccountIdOrderByStartAtAscIdAsc(
                        subject.tenantId(), subject.accountId(), pageRequest(limit))
                .stream()
                .map(this::eventResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarEventResponse getEvent(CalendarSubject subject, UUID eventId) {
        CalendarEvent event = eventRepository.findById(eventId).orElse(null);
        accessService.authorize(
                subject,
                CalendarAuthorizationActions.EVENT_READ,
                CalendarAuthorizationResource.existingEvent(eventId, event == null ? null : event.getOwnerAccountId(), subject));
        if (event == null) {
            throw new CalendarResourceNotFoundException();
        }
        assertOwner(event.getOwnerAccountId(), event.getTenantId(), subject);
        return eventResponse(event);
    }

    public CalendarIdempotencyResult<CalendarEventResponse> updateEvent(
            CalendarSubject subject,
            UUID eventId,
            long expectedVersion,
            UpdateCalendarEventRequest request,
            String idempotencyKey) {
        CalendarEvent existing = eventRepository.findById(eventId).orElse(null);
        accessService.authorize(
                subject,
                CalendarAuthorizationActions.EVENT_UPDATE,
                CalendarAuthorizationResource.existingEvent(eventId, existing == null ? null : existing.getOwnerAccountId(), subject));
        if (existing == null) {
            throw new CalendarResourceNotFoundException();
        }
        assertOwner(existing.getOwnerAccountId(), existing.getTenantId(), subject);
        validateReminders(request.reminders());
        return idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                CalendarMutationOperation.EVENT_UPDATE,
                eventId.toString(),
                idempotencyKey,
                request,
                expectedVersion,
                CalendarEventResponse.class,
                200,
                "/api/v1/calendar/events/" + eventId,
                () -> updateEventOnce(subject, eventId, expectedVersion, request));
    }

    public CalendarIdempotencyResult<CalendarEventResponse> cancelEvent(
            CalendarSubject subject, UUID eventId, long expectedVersion, String idempotencyKey) {
        CalendarEvent existing = eventRepository.findById(eventId).orElse(null);
        accessService.authorize(
                subject,
                CalendarAuthorizationActions.EVENT_CANCEL,
                CalendarAuthorizationResource.existingEvent(eventId, existing == null ? null : existing.getOwnerAccountId(), subject));
        if (existing == null) {
            throw new CalendarResourceNotFoundException();
        }
        assertOwner(existing.getOwnerAccountId(), existing.getTenantId(), subject);
        CancelCommand command = new CancelCommand(eventId, expectedVersion);
        return idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                CalendarMutationOperation.EVENT_CANCEL,
                eventId.toString(),
                idempotencyKey,
                command,
                expectedVersion,
                CalendarEventResponse.class,
                200,
                "/api/v1/calendar/events/" + eventId,
                () -> {
                    CalendarEvent locked = eventRepository.findByIdForUpdate(eventId)
                            .orElseThrow(CalendarResourceNotFoundException::new);
                    assertOwner(locked.getOwnerAccountId(), locked.getTenantId(), subject);
                    verifyVersion(locked.getVersion(), expectedVersion);
                    Instant now = clock.instant();
                    cancelFutureEventWork(locked.getId(), now);
                    locked.cancel(now);
                    eventRepository.flush();
                    CalendarEventResponse response = eventResponse(locked);
                    auditService.record(subject, "calendar.event.cancel", CalendarAuditOutcome.SUCCESS, "calendar-event", eventId, null);
                    return response;
                });
    }

    public CalendarIdempotencyResult<CalendarTimeBlockResponse> createTimeBlock(
            CalendarSubject subject, CreateCalendarTimeBlockRequest request, String idempotencyKey) {
        UUID blockId = UUID.randomUUID();
        accessService.authorize(
                subject,
                CalendarAuthorizationActions.TIME_BLOCK_CREATE,
                CalendarAuthorizationResource.newTimeBlock(blockId, subject));
        verifyLinkedResource(subject, request.linkType(), request.linkedResourceId(), "calendar.time-block.create");
        return idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                CalendarMutationOperation.TIME_BLOCK_CREATE,
                "time-block:create",
                idempotencyKey,
                request,
                null,
                CalendarTimeBlockResponse.class,
                201,
                "/api/v1/calendar/time-blocks/" + blockId,
                () -> createTimeBlockOnce(subject, blockId, request));
    }

    @Transactional(readOnly = true)
    public List<CalendarTimeBlockResponse> listTimeBlocks(CalendarSubject subject, int limit) {
        accessService.authorize(subject, CalendarAuthorizationActions.TIME_BLOCK_LIST, CalendarAuthorizationResource.collection(subject));
        return timeBlockRepository
                .findByTenantIdAndOwnerAccountIdOrderByStartAtAscIdAsc(
                        subject.tenantId(), subject.accountId(), pageRequest(limit))
                .stream()
                .map(CalendarTimeBlockResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarTimeBlockResponse getTimeBlock(CalendarSubject subject, UUID blockId) {
        CalendarTimeBlock block = timeBlockRepository.findById(blockId).orElse(null);
        accessService.authorize(
                subject,
                CalendarAuthorizationActions.TIME_BLOCK_READ,
                CalendarAuthorizationResource.existingTimeBlock(blockId, block == null ? null : block.getOwnerAccountId(), subject));
        if (block == null) {
            throw new CalendarResourceNotFoundException();
        }
        assertOwner(block.getOwnerAccountId(), block.getTenantId(), subject);
        return CalendarTimeBlockResponse.from(block);
    }

    public CalendarIdempotencyResult<CalendarTimeBlockResponse> updateTimeBlock(
            CalendarSubject subject,
            UUID blockId,
            long expectedVersion,
            UpdateCalendarTimeBlockRequest request,
            String idempotencyKey) {
        CalendarTimeBlock existing = timeBlockRepository.findById(blockId).orElse(null);
        accessService.authorize(
                subject,
                CalendarAuthorizationActions.TIME_BLOCK_UPDATE,
                CalendarAuthorizationResource.existingTimeBlock(
                        blockId, existing == null ? null : existing.getOwnerAccountId(), subject));
        if (existing == null) {
            throw new CalendarResourceNotFoundException();
        }
        assertOwner(existing.getOwnerAccountId(), existing.getTenantId(), subject);
        verifyLinkedResource(subject, request.linkType(), request.linkedResourceId(), "calendar.time-block.update");
        return idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                CalendarMutationOperation.TIME_BLOCK_UPDATE,
                blockId.toString(),
                idempotencyKey,
                request,
                expectedVersion,
                CalendarTimeBlockResponse.class,
                200,
                "/api/v1/calendar/time-blocks/" + blockId,
                () -> updateTimeBlockOnce(subject, blockId, expectedVersion, request));
    }

    public CalendarIdempotencyResult<CalendarTimeBlockResponse> cancelTimeBlock(
            CalendarSubject subject, UUID blockId, long expectedVersion, String idempotencyKey) {
        CalendarTimeBlock existing = timeBlockRepository.findById(blockId).orElse(null);
        accessService.authorize(
                subject,
                CalendarAuthorizationActions.TIME_BLOCK_CANCEL,
                CalendarAuthorizationResource.existingTimeBlock(
                        blockId, existing == null ? null : existing.getOwnerAccountId(), subject));
        if (existing == null) {
            throw new CalendarResourceNotFoundException();
        }
        assertOwner(existing.getOwnerAccountId(), existing.getTenantId(), subject);
        CancelCommand command = new CancelCommand(blockId, expectedVersion);
        return idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                CalendarMutationOperation.TIME_BLOCK_CANCEL,
                blockId.toString(),
                idempotencyKey,
                command,
                expectedVersion,
                CalendarTimeBlockResponse.class,
                200,
                "/api/v1/calendar/time-blocks/" + blockId,
                () -> {
                    CalendarTimeBlock locked = timeBlockRepository.findByIdForUpdate(blockId)
                            .orElseThrow(CalendarResourceNotFoundException::new);
                    assertOwner(locked.getOwnerAccountId(), locked.getTenantId(), subject);
                    verifyVersion(locked.getVersion(), expectedVersion);
                    acquireScheduleLock(subject);
                    locked.cancel(clock.instant());
                    timeBlockRepository.flush();
                    CalendarTimeBlockResponse response = CalendarTimeBlockResponse.from(locked);
                    auditService.record(
                            subject, "calendar.time-block.cancel", CalendarAuditOutcome.SUCCESS, "calendar-time-block", blockId, null);
                    return response;
                });
    }

    private CalendarEventResponse updateEventOnce(
            CalendarSubject subject, UUID eventId, long expectedVersion, UpdateCalendarEventRequest request) {
        CalendarEvent event = eventRepository.findByIdForUpdate(eventId).orElseThrow(CalendarResourceNotFoundException::new);
        assertOwner(event.getOwnerAccountId(), event.getTenantId(), subject);
        verifyVersion(event.getVersion(), expectedVersion);
        Instant now = clock.instant();
        acquireScheduleLock(subject);
        cancelFutureEventWork(event.getId(), now);
        event.update(
                request.title(),
                request.description(),
                request.startAt(),
                request.endAt(),
                request.timeZone(),
                CalendarRecurrence.toStored(request.recurrence()),
                currentCorrelationId(),
                now);
        eventReminderRepository.deleteByEventId(event.getId());
        eventReminderRepository.flush();
        replaceReminderTemplates(event.getId(), request.reminders(), now);
        occurrenceFactory.create(event, event.getRecurrenceRevision(), event.getStartAt(), event.getEndAt(), now);
        eventRepository.flush();
        CalendarEventResponse response = eventResponse(event);
        auditService.record(subject, "calendar.event.update", CalendarAuditOutcome.SUCCESS, "calendar-event", event.getId(), null);
        return response;
    }

    private CalendarTimeBlockResponse createTimeBlockOnce(
            CalendarSubject subject, UUID blockId, CreateCalendarTimeBlockRequest request) {
        acquireScheduleLock(subject);
        List<CalendarConflict> conflicts = conflictDetector.detectForTimeBlock(
                subject.tenantId(), subject.accountId(), null, request.startAt(), request.endAt());
        if (!conflicts.isEmpty()) {
            auditService.record(
                    subject,
                    "calendar.time-block.create",
                    CalendarAuditOutcome.FAILURE,
                    "calendar-time-block",
                    blockId,
                    "SCHEDULE_CONFLICT");
            throw new CalendarConflictException(conflicts);
        }
        CalendarTimeBlock block = timeBlockRepository.save(CalendarTimeBlock.active(
                blockId,
                subject.accountId(),
                subject.tenantId(),
                request.linkType(),
                request.linkedResourceId(),
                request.startAt(),
                request.endAt(),
                request.timeZone(),
                clock.instant()));
        timeBlockRepository.flush();
        CalendarTimeBlockResponse response = CalendarTimeBlockResponse.from(block);
        auditService.record(
                subject, "calendar.time-block.create", CalendarAuditOutcome.SUCCESS, "calendar-time-block", blockId, null);
        return response;
    }

    private CalendarTimeBlockResponse updateTimeBlockOnce(
            CalendarSubject subject, UUID blockId, long expectedVersion, UpdateCalendarTimeBlockRequest request) {
        CalendarTimeBlock block = timeBlockRepository.findByIdForUpdate(blockId)
                .orElseThrow(CalendarResourceNotFoundException::new);
        assertOwner(block.getOwnerAccountId(), block.getTenantId(), subject);
        verifyVersion(block.getVersion(), expectedVersion);
        acquireScheduleLock(subject);
        List<CalendarConflict> conflicts = conflictDetector.detectForTimeBlock(
                subject.tenantId(), subject.accountId(), block.getId(), request.startAt(), request.endAt());
        if (!conflicts.isEmpty()) {
            auditService.record(
                    subject,
                    "calendar.time-block.update",
                    CalendarAuditOutcome.FAILURE,
                    "calendar-time-block",
                    blockId,
                    "SCHEDULE_CONFLICT");
            throw new CalendarConflictException(conflicts);
        }
        block.update(
                request.linkType(), request.linkedResourceId(), request.startAt(), request.endAt(), request.timeZone(), clock.instant());
        timeBlockRepository.flush();
        CalendarTimeBlockResponse response = CalendarTimeBlockResponse.from(block);
        auditService.record(
                subject,
                "calendar.time-block.update",
                CalendarAuditOutcome.SUCCESS,
                "calendar-time-block",
                block.getId(),
                null);
        return response;
    }

    private void cancelFutureEventWork(UUID eventId, Instant now) {
        occurrenceRepository.findByEventId(eventId).stream()
                .filter(occurrence -> !occurrence.getStartAt().isBefore(now))
                .forEach(occurrence -> occurrence.cancel());
        reminderRepository.findByEventIdAndStateIn(
                eventId, List.of(CalendarReminderState.SCHEDULED, CalendarReminderState.LEASED)).forEach(reminder -> reminder.cancel(now));
        var reminderIds = reminderRepository.findByEventId(eventId).stream().map(reminder -> reminder.getId()).toList();
        if (!reminderIds.isEmpty()) {
            outboxRepository.findByReminderIdIn(reminderIds).forEach(outbox -> outbox.cancel());
        }
        // Future materialized occurrences are derived state and must not survive a series revision or cancellation.
        // Past history remains durable for audit/debugging; the recurrence worker only creates the new revision.
        eventRepository.flush();
    }

    private void replaceReminderTemplates(UUID eventId, List<CalendarReminderRequest> reminders, Instant now) {
        List<CalendarReminderRequest> safeReminders = reminders == null ? List.of() : reminders;
        safeReminders.forEach(reminder -> eventReminderRepository.save(
                CalendarEventReminder.of(eventId, reminder.minutesBefore(), reminder.requestedChannels(), now)));
    }

    private CalendarEventResponse eventResponse(CalendarEvent event) {
        return CalendarEventResponse.from(event, eventReminderRepository.findByEventIdOrderByMinutesBeforeAsc(event.getId()));
    }

    private void acquireScheduleLock(CalendarSubject subject) {
        scheduleLockService.acquire(subject);
    }

    private static void verifyVersion(long current, long expected) {
        if (current != expected) {
            throw new CalendarVersionConflictException();
        }
    }

    private static void assertOwner(UUID ownerAccountId, String resourceTenantId, CalendarSubject subject) {
        if (!subject.accountId().equals(ownerAccountId)
                || !subject.tenantId().equals(resourceTenantId)
                || !subject.tenantId().equals(ownerAccountId.toString())) {
            throw new CalendarResourceNotFoundException();
        }
    }

    private void verifyLinkedResource(
            CalendarSubject subject,
            com.lifeos.calendar.domain.CalendarLinkType linkType,
            UUID linkedResourceId,
            String auditAction) {
        if (linkType != com.lifeos.calendar.domain.CalendarLinkType.FOCUS) {
            if (linkedResourceId == null) {
                throw new UnsupportedCalendarLinkException();
            }
            try {
                taskGoalProjection.verify(subject, linkType, linkedResourceId);
                return;
            } catch (UnsupportedCalendarLinkException exception) {
                auditService.record(
                        subject,
                        auditAction,
                        CalendarAuditOutcome.DENY,
                        "calendar-time-block",
                        null,
                        "TASK_GOAL_PROJECTION_UNAVAILABLE");
                throw exception;
            }
        }
        if (linkedResourceId != null) {
            auditService.record(
                    subject,
                    auditAction,
                    CalendarAuditOutcome.DENY,
                    "calendar-time-block",
                    null,
                    "INVALID_FOCUS_LINK");
            throw new UnsupportedCalendarLinkException();
        }
    }

    private static void validateReminders(List<CalendarReminderRequest> reminders) {
        if (reminders == null) {
            return;
        }
        HashSet<Integer> offsets = new HashSet<>();
        for (CalendarReminderRequest reminder : reminders) {
            if (reminder == null || !offsets.add(reminder.minutesBefore())) {
                throw new IllegalArgumentException("calendar reminder offsets must be unique");
            }
        }
    }

    private static PageRequest pageRequest(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("calendar list limit must be between one and 200");
        }
        return PageRequest.of(0, limit);
    }

    private static UUID currentCorrelationId() {
        if (com.lifeos.calendar.observability.RequestContext.CORRELATION_ID.isBound()) {
            return UUID.fromString(com.lifeos.calendar.observability.RequestContext.CORRELATION_ID.get());
        }
        return UUID.randomUUID();
    }

    private record CancelCommand(UUID id, long expectedVersion) {
    }
}

package com.lifeos.calendar.api;

import com.lifeos.calendar.authorization.CalendarAccessService;
import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.idempotency.CalendarIdempotencyKey;
import com.lifeos.calendar.idempotency.CalendarIdempotencyResult;
import com.lifeos.calendar.idempotency.CalendarVersionPrecondition;
import com.lifeos.calendar.service.CalendarManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned public lifecycle API for owner-scoped calendar events. */
@RestController
public class CalendarEventController {

    private final CalendarManagementService service;
    private final CalendarAccessService accessService;

    public CalendarEventController(CalendarManagementService service, CalendarAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping("/api/v1/calendar/events")
    public ResponseEntity<CalendarEventResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = CalendarIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody CreateCalendarEventRequest request) {
        CalendarIdempotencyResult<CalendarEventResponse> result = service.createEvent(
                authenticate(authorization), request, CalendarIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @GetMapping("/api/v1/calendar/events")
    public List<CalendarEventResponse> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return service.listEvents(authenticate(authorization), limit);
    }

    @GetMapping("/api/v1/calendar/events/{eventId}")
    public ResponseEntity<CalendarEventResponse> get(
            @PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CalendarEventResponse body = service.getEvent(authenticate(authorization), eventId);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/calendar/events/{eventId}")
    public ResponseEntity<CalendarEventResponse> update(
            @PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = CalendarIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = CalendarVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdateCalendarEventRequest request) {
        CalendarIdempotencyResult<CalendarEventResponse> result = service.updateEvent(
                authenticate(authorization),
                eventId,
                CalendarVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                CalendarIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @PostMapping("/api/v1/calendar/events/{eventId}/cancel")
    public ResponseEntity<CalendarEventResponse> cancel(
            @PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = CalendarIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = CalendarVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch) {
        CalendarIdempotencyResult<CalendarEventResponse> result = service.cancelEvent(
                authenticate(authorization),
                eventId,
                CalendarVersionPrecondition.requireSingleHeader(ifMatch),
                CalendarIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    private CalendarSubject authenticate(String authorization) {
        return accessService.authenticate(authorization);
    }

    private static ResponseEntity<CalendarEventResponse> mutationResponse(
            CalendarIdempotencyResult<CalendarEventResponse> result, long version) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status()).eTag(etag(version));
        if (result.location() != null) {
            builder.location(URI.create(result.location()));
        }
        if (result.replayed()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(result.body());
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

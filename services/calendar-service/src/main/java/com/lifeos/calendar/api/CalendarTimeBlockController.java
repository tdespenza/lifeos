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

/** Versioned public lifecycle API for conflict-protected time blocks. */
@RestController
public class CalendarTimeBlockController {

    private final CalendarManagementService service;
    private final CalendarAccessService accessService;

    public CalendarTimeBlockController(CalendarManagementService service, CalendarAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping("/api/v1/calendar/time-blocks")
    public ResponseEntity<CalendarTimeBlockResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = CalendarIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody CreateCalendarTimeBlockRequest request) {
        CalendarIdempotencyResult<CalendarTimeBlockResponse> result = service.createTimeBlock(
                authenticate(authorization), request, CalendarIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @GetMapping("/api/v1/calendar/time-blocks")
    public List<CalendarTimeBlockResponse> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return service.listTimeBlocks(authenticate(authorization), limit);
    }

    @GetMapping("/api/v1/calendar/time-blocks/{blockId}")
    public ResponseEntity<CalendarTimeBlockResponse> get(
            @PathVariable UUID blockId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CalendarTimeBlockResponse body = service.getTimeBlock(authenticate(authorization), blockId);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/calendar/time-blocks/{blockId}")
    public ResponseEntity<CalendarTimeBlockResponse> update(
            @PathVariable UUID blockId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = CalendarIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = CalendarVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdateCalendarTimeBlockRequest request) {
        CalendarIdempotencyResult<CalendarTimeBlockResponse> result = service.updateTimeBlock(
                authenticate(authorization),
                blockId,
                CalendarVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                CalendarIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @PostMapping("/api/v1/calendar/time-blocks/{blockId}/cancel")
    public ResponseEntity<CalendarTimeBlockResponse> cancel(
            @PathVariable UUID blockId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = CalendarIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = CalendarVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch) {
        CalendarIdempotencyResult<CalendarTimeBlockResponse> result = service.cancelTimeBlock(
                authenticate(authorization),
                blockId,
                CalendarVersionPrecondition.requireSingleHeader(ifMatch),
                CalendarIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    private CalendarSubject authenticate(String authorization) {
        return accessService.authenticate(authorization);
    }

    private static ResponseEntity<CalendarTimeBlockResponse> mutationResponse(
            CalendarIdempotencyResult<CalendarTimeBlockResponse> result, long version) {
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

package com.lifeos.notification.api;

import com.lifeos.notification.access.NotificationAccessService;
import com.lifeos.notification.access.NotificationSubject;
import com.lifeos.notification.read.NotificationReadService;
import com.lifeos.notification.read.NotificationView;
import com.lifeos.notification.stream.NotificationStreamHub;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Recipient-owned REST history and resumable SSE notification surface. */
@RestController
public class NotificationController {

    private final NotificationAccessService accessService;
    private final NotificationReadService readService;
    private final NotificationStreamHub streamHub;

    public NotificationController(
            NotificationAccessService accessService, NotificationReadService readService, NotificationStreamHub streamHub) {
        this.accessService = accessService;
        this.readService = readService;
        this.streamHub = streamHub;
    }

    @GetMapping("/api/v1/notifications")
    public NotificationPageResponse list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        NotificationSubject subject = accessService.authenticate(authorizationHeader);
        List<NotificationView> items = readService.list(subject, after, limit);
        long nextCursor = items.isEmpty() ? after : items.getLast().sequence();
        return new NotificationPageResponse(items, nextCursor);
    }

    @GetMapping(value = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        NotificationSubject subject = accessService.authenticate(authorizationHeader);
        long after = parseLastEventId(lastEventId);
        return streamHub.open(subject.accountId(), readService.replayForStream(subject, after));
    }

    private static long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative cursor");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a nonnegative notification sequence");
        }
    }
}

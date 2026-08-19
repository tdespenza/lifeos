package com.lifeos.notification.read;

import com.lifeos.notification.access.NotificationSubject;
import com.lifeos.notification.config.NotificationProperties;
import com.lifeos.notification.persistence.NotificationRecord;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import com.lifeos.notification.stream.NotificationStreamResyncRequiredException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Owner-scoped bounded notification history and SSE replay reads. */
@Service
public class NotificationReadService {

    private static final int MAX_LIST_LIMIT = 100;

    private final NotificationRecordRepository repository;
    private final NotificationProperties properties;

    public NotificationReadService(NotificationRecordRepository repository, NotificationProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public List<NotificationView> list(NotificationSubject subject, long after, int limit) {
        validateCursor(after);
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("limit must be between one and one hundred");
        }
        return repository
                .findByRecipientAccountIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        subject.accountId(), after, PageRequest.of(0, limit))
                .stream()
                .map(NotificationView::from)
                .toList();
    }

    /**
     * A stream never tries to queue an unbounded history. Callers receive a controlled resync
     * response and use the paginated REST endpoint when the replay window is exceeded.
     */
    public List<NotificationView> replayForStream(NotificationSubject subject, long after) {
        validateCursor(after);
        int limit = properties.getStream().getReplayLimit();
        List<NotificationRecord> records = repository
                .findByRecipientAccountIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        subject.accountId(), after, PageRequest.of(0, limit + 1));
        if (records.size() > limit) {
            throw new NotificationStreamResyncRequiredException();
        }
        return records.stream().map(NotificationView::from).toList();
    }

    private static void validateCursor(long after) {
        if (after < 0) {
            throw new IllegalArgumentException("notification cursor must not be negative");
        }
    }
}

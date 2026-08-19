package com.lifeos.notification.stream;

import com.lifeos.notification.config.NotificationProperties;
import com.lifeos.notification.read.NotificationView;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bounded per-account SSE fanout hub. Every session has a fixed queue and virtual dispatcher;
 * overflow or an out-of-order gap closes the stream so the client must resume from its last event
 * ID through the durable REST history rather than accumulating unbounded server memory.
 */
@Component
public class NotificationStreamHub {

    private final NotificationProperties properties;
    private final ExecutorService dispatchers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<UUID, Set<StreamSession>> sessions = new ConcurrentHashMap<>();

    public NotificationStreamHub(NotificationProperties properties) {
        this.properties = properties;
    }

    public SseEmitter open(UUID accountId, List<NotificationView> initialEvents) {
        if (initialEvents.size() > properties.getStream().getQueueCapacity()) {
            throw new NotificationStreamResyncRequiredException();
        }
        Set<StreamSession> accountSessions = sessions.computeIfAbsent(accountId, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (accountSessions) {
            if (accountSessions.size() >= properties.getStream().getMaxConnectionsPerAccount()) {
                throw new NotificationStreamCapacityExceededException();
            }
            StreamSession session = new StreamSession(accountId, initialEvents);
            accountSessions.add(session);
            session.start();
            return session.emitter();
        }
    }

    /** Publishes only the next contiguous recipient sequence; gaps deliberately require resync. */
    public void publish(UUID accountId, NotificationView notification) {
        Set<StreamSession> accountSessions = sessions.get(accountId);
        if (accountSessions == null) {
            return;
        }
        for (StreamSession session : List.copyOf(accountSessions)) {
            session.enqueueNotification(notification);
        }
    }

    @Scheduled(fixedDelayString = "${notification.stream.heartbeat-interval:15s}")
    public void enqueueHeartbeats() {
        sessions.values().forEach(accountSessions -> List.copyOf(accountSessions).forEach(StreamSession::enqueueHeartbeat));
    }

    @PreDestroy
    void close() {
        sessions.values().forEach(accountSessions -> new ArrayList<>(accountSessions).forEach(StreamSession::close));
        dispatchers.shutdownNow();
    }

    private final class StreamSession {

        private final UUID accountId;
        private final SseEmitter emitter = new SseEmitter(properties.getStream().getConnectionTimeout().toMillis());
        private final ArrayBlockingQueue<StreamItem> queue =
                new ArrayBlockingQueue<>(properties.getStream().getQueueCapacity());
        private final AtomicBoolean open = new AtomicBoolean(true);
        private long lastQueuedSequence;

        private StreamSession(UUID accountId, List<NotificationView> initialEvents) {
            this.accountId = accountId;
            for (NotificationView event : initialEvents) {
                if (lastQueuedSequence != 0 && event.sequence() != lastQueuedSequence + 1) {
                    throw new NotificationStreamResyncRequiredException();
                }
                queue.add(StreamItem.notification(event));
                lastQueuedSequence = event.sequence();
            }
            emitter.onCompletion(this::close);
            emitter.onTimeout(this::close);
            emitter.onError(ignored -> close());
        }

        private SseEmitter emitter() {
            return emitter;
        }

        private void start() {
            dispatchers.submit(this::dispatch);
        }

        private synchronized void enqueueNotification(NotificationView event) {
            if (!open.get() || event.sequence() <= lastQueuedSequence) {
                return;
            }
            if (lastQueuedSequence != 0 && event.sequence() != lastQueuedSequence + 1) {
                close();
                return;
            }
            if (!queue.offer(StreamItem.notification(event))) {
                close();
                return;
            }
            lastQueuedSequence = event.sequence();
        }

        private void enqueueHeartbeat() {
            if (open.get() && !queue.offer(StreamItem.heartbeat())) {
                close();
            }
        }

        private void dispatch() {
            try {
                while (open.get()) {
                    StreamItem item = queue.poll(
                            properties.getStream().getConnectionTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    if (item == null) {
                        close();
                        return;
                    }
                    send(item);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                close();
            }
        }

        private void send(StreamItem item) {
            try {
                if (item.notification() == null) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } else {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(item.notification().sequence()))
                            .name("notification")
                            .data(item.notification()));
                }
            } catch (IOException | IllegalStateException exception) {
                close();
            }
        }

        private void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            sessions.computeIfPresent(accountId, (ignored, accountSessions) -> {
                accountSessions.remove(this);
                return accountSessions.isEmpty() ? null : accountSessions;
            });
            emitter.complete();
        }
    }

    private record StreamItem(NotificationView notification) {

        private static StreamItem notification(NotificationView notification) {
            return new StreamItem(notification);
        }

        private static StreamItem heartbeat() {
            return new StreamItem(null);
        }
    }
}

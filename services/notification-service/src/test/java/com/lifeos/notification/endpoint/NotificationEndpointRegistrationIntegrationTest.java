package com.lifeos.notification.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.notification.access.NotificationSubject;
import com.lifeos.notification.audit.NotificationSecurityAuditEventRepository;
import com.lifeos.notification.audit.NotificationSecurityAuditOutcome;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationEndpointRegistrationIntegrationTest {

    @Autowired
    private NotificationEndpointService endpointService;

    @Autowired
    private NotificationEndpointRepository endpointRepository;

    @Autowired
    private NotificationSecurityAuditEventRepository auditRepository;

    @Test
    void encryptsEndpointAtRestAndReplaysTheSameIdempotencyKey() {
        NotificationSubject subject = new NotificationSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
        String key = "endpoint-idempotency-key-0000000001";

        EndpointRegistrationResult first =
                endpointService.register(subject, NotificationChannel.EMAIL, "Person@Example.Test", key);
        EndpointRegistrationResult replay =
                endpointService.register(subject, NotificationChannel.EMAIL, "person@example.test", key);

        assertFalse(first.duplicate());
        assertEquals(first.endpoint().getId(), replay.endpoint().getId());
        assertEquals(1, endpointRepository.count());
        assertNotEquals("person@example.test", first.endpoint().getDestinationCiphertext());
        assertEquals(
                2,
                auditRepository.countByEventTypeAndOutcome(
                        "ENDPOINT_ENROLLMENT", NotificationSecurityAuditOutcome.SUCCESS));
    }
}

package com.lifeos.notification.api;

import com.lifeos.notification.access.NotificationAccessService;
import com.lifeos.notification.access.NotificationSubject;
import com.lifeos.notification.endpoint.EndpointIdempotencyKey;
import com.lifeos.notification.endpoint.EndpointRegistrationResult;
import com.lifeos.notification.endpoint.NotificationEndpointService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Self-owned encrypted endpoint enrollment and revocation API. */
@RestController
public class NotificationEndpointController {

    private final NotificationAccessService accessService;
    private final NotificationEndpointService endpointService;

    public NotificationEndpointController(NotificationAccessService accessService, NotificationEndpointService endpointService) {
        this.accessService = accessService;
        this.endpointService = endpointService;
    }

    @PostMapping("/api/v1/notification-endpoints")
    public ResponseEntity<NotificationEndpointResponse> register(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = EndpointIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody RegisterNotificationEndpointRequest request) {
        NotificationSubject subject = accessService.authenticate(authorizationHeader);
        EndpointRegistrationResult result = endpointService.register(
                subject,
                request.channel(),
                request.destination(),
                EndpointIdempotencyKey.requireSingleHeader(idempotencyKeys));
        NotificationEndpointResponse response = NotificationEndpointResponse.from(result.endpoint());
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/notification-endpoints/" + response.id()))
                .eTag(etag(response.version()))
                .body(response);
    }

    @GetMapping("/api/v1/notification-endpoints")
    public List<NotificationEndpointResponse> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        NotificationSubject subject = accessService.authenticate(authorizationHeader);
        return endpointService.list(subject).stream().map(NotificationEndpointResponse::from).toList();
    }

    @DeleteMapping("/api/v1/notification-endpoints/{endpointId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID endpointId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        endpointService.revoke(accessService.authenticate(authorizationHeader), endpointId);
        return ResponseEntity.noContent().build();
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

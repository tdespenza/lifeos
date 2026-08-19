package com.lifeos.media.api;

import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.idempotency.MediaIdempotencyKey;
import com.lifeos.media.idempotency.MediaIdempotencyResult;
import com.lifeos.media.idempotency.MediaVersionPrecondition;
import com.lifeos.media.service.MediaManagementService;
import com.lifeos.media.service.MediaPostSessionService;
import com.lifeos.media.service.MediaFollowUpTaskService;
import com.lifeos.media.service.MediaSessionSummaryAnchorService;
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

/** Versioned owner-only session scheduling and bounded join bootstrap endpoints. */
@RestController
public class MediaSessionController {

    private final MediaManagementService service;
    private final MediaPostSessionService postSessionService;
    private final MediaAccessService accessService;
    private final MediaFollowUpTaskService followUpTaskService;
    private final MediaSessionSummaryAnchorService summaryAnchorService;

    public MediaSessionController(
            MediaManagementService service,
            MediaPostSessionService postSessionService,
            MediaAccessService accessService,
            MediaFollowUpTaskService followUpTaskService,
            MediaSessionSummaryAnchorService summaryAnchorService) {
        this.service = service;
        this.postSessionService = postSessionService;
        this.accessService = accessService;
        this.followUpTaskService = followUpTaskService;
        this.summaryAnchorService = summaryAnchorService;
    }

    @PostMapping("/api/v1/media/sessions")
    public ResponseEntity<MediaSessionResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody CreateMediaSessionRequest request) {
        MediaIdempotencyResult<MediaSessionResponse> result = service.createSession(
                authenticate(authorization), request, MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @GetMapping("/api/v1/media/sessions")
    public List<MediaSessionResponse> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return service.listSessions(authenticate(authorization), limit);
    }

    @GetMapping("/api/v1/media/sessions/{sessionId}")
    public ResponseEntity<MediaSessionResponse> get(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        MediaSessionResponse body = service.getSession(authenticate(authorization), sessionId);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/media/sessions/{sessionId}")
    public ResponseEntity<MediaSessionResponse> update(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = MediaVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody UpdateMediaSessionRequest request) {
        MediaIdempotencyResult<MediaSessionResponse> result = service.updateSession(
                authenticate(authorization),
                sessionId,
                MediaVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @PostMapping("/api/v1/media/sessions/{sessionId}/cancel")
    public ResponseEntity<MediaSessionResponse> cancel(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = MediaVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch) {
        MediaIdempotencyResult<MediaSessionResponse> result = service.cancelSession(
                authenticate(authorization),
                sessionId,
                MediaVersionPrecondition.requireSingleHeader(ifMatch),
                MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @PostMapping("/api/v1/media/sessions/{sessionId}/join")
    public ResponseEntity<MediaJoinResponse> join(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().body(service.joinSession(authenticate(authorization), sessionId));
    }

    @PostMapping("/api/v1/media/sessions/{sessionId}/post-session")
    public ResponseEntity<MediaSessionArtifactResponse> postSession(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody PostSessionArtifactRequest request) {
        MediaIdempotencyResult<MediaSessionArtifactResponse> result = postSessionService.process(
                authenticate(authorization),
                sessionId,
                request,
                MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status())
                .eTag(etag(result.body().version()));
        if (result.location() != null) builder.location(URI.create(result.location()));
        if (result.replayed()) builder.header("Idempotent-Replay", "true");
        return builder.body(result.body());
    }

    @GetMapping("/api/v1/media/sessions/{sessionId}/post-session")
    public ResponseEntity<MediaSessionArtifactResponse> getPostSession(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        MediaSessionArtifactResponse body = postSessionService.get(authenticate(authorization), sessionId);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PostMapping("/api/v1/media/sessions/{sessionId}/post-session/tasks")
    public ResponseEntity<FollowUpTaskResponse> confirmActionItem(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = MediaVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody ConfirmSessionActionRequest request) {
        MediaIdempotencyResult<FollowUpTaskResponse> result = followUpTaskService.confirm(
                authenticate(authorization), sessionId, MediaVersionPrecondition.requireSingleHeader(ifMatch), request,
                MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status())
                .eTag(etag(result.body().version()));
        builder.location(URI.create("/api/v1/tasks/" + result.body().id()));
        if (result.replayed()) builder.header("Idempotent-Replay", "true");
        return builder.body(result.body());
    }

    @PostMapping("/api/v1/media/sessions/{sessionId}/post-session/anchor")
    public ResponseEntity<MediaSessionAnchorResponse> anchorPostSession(
            @PathVariable UUID sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = MediaVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch) {
        MediaIdempotencyResult<MediaSessionAnchorResponse> result = summaryAnchorService.anchor(
                authenticate(authorization),
                sessionId,
                MediaVersionPrecondition.requireSingleHeader(ifMatch),
                MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status())
                .eTag(etag(result.body().artifactVersion()));
        if (result.location() != null) builder.location(URI.create(result.location()));
        if (result.replayed()) builder.header("Idempotent-Replay", "true");
        return builder.body(result.body());
    }

    private MediaSubject authenticate(String authorization) {
        return accessService.authenticate(authorization);
    }

    private static ResponseEntity<MediaSessionResponse> mutationResponse(
            MediaIdempotencyResult<MediaSessionResponse> result, long version) {
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

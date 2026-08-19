package com.lifeos.media.api;

import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.idempotency.MediaIdempotencyKey;
import com.lifeos.media.idempotency.MediaIdempotencyResult;
import com.lifeos.media.idempotency.MediaVersionPrecondition;
import com.lifeos.media.service.MediaManagementService;
import com.lifeos.media.storage.MediaObjectStorageException;
import com.lifeos.media.storage.MediaReadObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Owner-scoped metadata, bounded source upload, and private HLS delivery endpoints. */
@RestController
public class MediaAssetController {

    private final MediaManagementService service;
    private final MediaAccessService accessService;

    public MediaAssetController(MediaManagementService service, MediaAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping("/api/v1/media/assets")
    public ResponseEntity<MediaAssetResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody CreateMediaAssetRequest request) {
        MediaIdempotencyResult<MediaAssetResponse> result = service.createAsset(
                authenticate(authorization), request, MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, result.body().version());
    }

    @PutMapping(path = "/api/v1/media/assets/{assetId}/source", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaAssetResponse> uploadSource(
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = MediaIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = MediaVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("media source must not be empty");
        }
        try {
            MediaIdempotencyResult<MediaAssetResponse> result = service.uploadAssetSource(
                    authenticate(authorization),
                    assetId,
                    MediaVersionPrecondition.requireSingleHeader(ifMatch),
                    file.getContentType(),
                    file.getInputStream(),
                    MediaIdempotencyKey.requireSingleHeader(idempotencyKeys));
            return mutationResponse(result, result.body().version());
        } catch (IOException exception) {
            throw new MediaObjectStorageException(exception);
        }
    }

    @GetMapping("/api/v1/media/assets")
    public List<MediaAssetResponse> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return service.listAssets(authenticate(authorization), limit);
    }

    @GetMapping("/api/v1/media/assets/{assetId}")
    public ResponseEntity<MediaAssetResponse> get(
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        MediaAssetResponse body = service.getAsset(authenticate(authorization), assetId);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @GetMapping("/api/v1/media/assets/{assetId}/hls/master.m3u8")
    public ResponseEntity<StreamingResponseBody> manifest(
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return hlsResponse(service.openHlsManifest(authenticate(authorization), assetId));
    }

    @GetMapping("/api/v1/media/assets/{assetId}/hls/segments/{segmentName}")
    public ResponseEntity<StreamingResponseBody> segment(
            @PathVariable UUID assetId,
            @PathVariable String segmentName,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return hlsResponse(service.openHlsSegment(authenticate(authorization), assetId, segmentName));
    }

    private MediaSubject authenticate(String authorization) {
        return accessService.authenticate(authorization);
    }

    private static ResponseEntity<MediaAssetResponse> mutationResponse(
            MediaIdempotencyResult<MediaAssetResponse> result, long version) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status()).eTag(etag(version));
        if (result.location() != null) {
            builder.location(URI.create(result.location()));
        }
        if (result.replayed()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(result.body());
    }

    private static ResponseEntity<StreamingResponseBody> hlsResponse(MediaReadObject content) {
        StreamingResponseBody body = output -> {
            try (content) {
                content.inputStream().transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.contentLength())
                .body(body);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

package com.lifeos.documentvault.api;

import com.lifeos.documentvault.authorization.DocumentVaultAccessService;
import com.lifeos.documentvault.authorization.DocumentVaultSubject;
import com.lifeos.documentvault.domain.DocumentClassification;
import com.lifeos.documentvault.domain.DocumentMetadata;
import com.lifeos.documentvault.domain.DocumentSource;
import com.lifeos.documentvault.idempotency.DocumentCommandResult;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyKey;
import com.lifeos.documentvault.idempotency.DocumentVersionPrecondition;
import com.lifeos.documentvault.proof.DocumentProofRequestResponse;
import com.lifeos.documentvault.service.DocumentSearchQuery;
import com.lifeos.documentvault.service.DocumentVaultManagementService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Owner-scoped Document Vault metadata API. This foundation intentionally exposes no download URL. */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentVaultController {

    private final DocumentVaultManagementService service;
    private final DocumentVaultAccessService accessService;

    public DocumentVaultController(DocumentVaultManagementService service, DocumentVaultAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = DocumentIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestPart("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "documentTimestamp", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant documentTimestamp,
            @RequestParam(value = "source", defaultValue = "UPLOAD") DocumentSource source,
            @RequestParam(value = "classification", defaultValue = "PRIVATE") DocumentClassification classification)
            throws IOException {
        DocumentCommandResult result = service.upload(
                authenticate(authorizationHeader),
                file.getInputStream(),
                file.getContentType(),
                new DocumentMetadata(title, tags, documentTimestamp, source, classification),
                DocumentIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, true);
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        DocumentResponse body = DocumentResponse.from(service.get(authenticate(authorizationHeader), documentId));
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping(path = "/{documentId}/metadata", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentResponse> updateMetadata(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = DocumentIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = DocumentVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatchValues,
            @Valid @org.springframework.web.bind.annotation.RequestBody UpdateDocumentMetadataRequest request) {
        DocumentCommandResult result = service.updateMetadata(
                authenticate(authorizationHeader),
                documentId,
                DocumentVersionPrecondition.requireSingleHeader(ifMatchValues),
                request.toMetadata(),
                DocumentIdempotencyKey.requireSingleHeader(idempotencyKeys));
        return mutationResponse(result, false);
    }

    @GetMapping("/search")
    public DocumentSearchResponse search(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return DocumentSearchResponse.from(service.search(
                authenticate(authorizationHeader), new DocumentSearchQuery(query, page, size)));
    }

    @PostMapping("/{documentId}/proof-requests")
    public ResponseEntity<DocumentProofRequestResponse> requestProof(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = DocumentIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys) {
        DocumentProofRequestResponse result = service.requestProof(
                authenticate(authorizationHeader),
                documentId,
                DocumentIdempotencyKey.requireSingleHeader(idempotencyKeys));
        ResponseEntity.BodyBuilder builder = ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION,
                        "/api/v1/documents/" + documentId + "/proof-requests/" + result.requestId());
        if (result.replayed()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(result);
    }

    @GetMapping("/{documentId}/proof-requests/{requestId}")
    public DocumentProofRequestResponse getProofRequest(
            @PathVariable UUID documentId,
            @PathVariable UUID requestId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        DocumentProofRequestResponse result = service.getProofRequest(authenticate(authorizationHeader), requestId);
        if (!documentId.equals(result.documentId())) {
            throw new com.lifeos.documentvault.service.DocumentResourceUnavailableException();
        }
        return result;
    }

    private DocumentVaultSubject authenticate(String authorizationHeader) {
        return accessService.authenticate(authorizationHeader);
    }

    private static ResponseEntity<DocumentResponse> mutationResponse(DocumentCommandResult result, boolean created) {
        DocumentResponse body = DocumentResponse.from(result.document());
        ResponseEntity.BodyBuilder builder = created
                ? ResponseEntity.created(URI.create("/api/v1/documents/" + body.id()))
                : ResponseEntity.ok();
        builder.eTag(etag(body.version()));
        if (result.replayed()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(body);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}

package com.lifeos.analytics.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Stable, non-sensitive error envelope for dashboard callers. */
@RestControllerAdvice
public class AnalyticsExceptionHandler {

    @ExceptionHandler(AnalyticsUnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "UNAUTHENTICATED", "message", "authentication is required"));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", "request is invalid"));
    }
}

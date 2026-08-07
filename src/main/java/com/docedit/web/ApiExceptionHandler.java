package com.docedit.web;

import com.docedit.exception.ChangeException;
import com.docedit.exception.DocumentNotFoundException;
import com.docedit.exception.VersionConflictException;
import com.docedit.payload.response.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates internal exception types into HTTP responses with a uniform
 * { error, code } body. Centralising this lets the store and engine raise plain
 * exceptions without knowing anything about HTTP, and cleanly separates client
 * errors (4xx) from anything unexpected (which falls through to a 500).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Unknown document id becomes a 404. */
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(final DocumentNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /** A stale If-Match version becomes a 412. */
    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(final VersionConflictException exception) {
        return build(HttpStatus.PRECONDITION_FAILED, exception.getMessage());
    }

    /** An invalid or inapplicable change becomes a 422. */
    @ExceptionHandler(ChangeException.class)
    public ResponseEntity<ErrorResponse> handleBadChange(final ChangeException exception) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }

    /** A payload that fails bean validation becomes a 422. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPayload(final MethodArgumentNotValidException exception) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "invalid payload: " + exception.getMessage());
    }

    /** Malformed or unparseable JSON body becomes a 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(final HttpMessageNotReadableException exception) {
        return build(HttpStatus.BAD_REQUEST, "malformed request body");
    }

    /** A missing required query parameter (e.g. search q) becomes a 400. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(final MissingServletRequestParameterException exception) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /** Anything unexpected becomes a 500 with the same { error, code } shape, and is logged. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(final Exception exception) {
        LOG.error("Unhandled exception", exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
    }

    /** Builds the { error, code } response for a status and message. */
    private static ResponseEntity<ErrorResponse> build(final HttpStatus status, final String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message, status.value()));
    }
}

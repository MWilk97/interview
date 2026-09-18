package com.mwilk.ledger.app.web;

import com.mwilk.ledger.core.IdempotencyCapacityExceededException;
import com.mwilk.ledger.core.IdempotencyKeyConflictException;
import com.mwilk.ledger.core.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps domain and validation failures to RFC 9457 problem responses. Framework exceptions (missing header,
 * malformed JSON, bean validation) are already translated by the superclass.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, "Unknown account", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ProblemDetail idempotencyKeyConflict(IdempotencyKeyConflictException e) {
        return Problems.of(HttpStatus.CONFLICT, "Idempotency key conflict", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(IdempotencyCapacityExceededException.class)
    ProblemDetail idempotencyCapacityExceeded(IdempotencyCapacityExceededException e) {
        log.warn("Refusing new idempotency keys", e);
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "Try again later",
                "The service cannot accept new idempotency keys right now.");
    }

    /**
     * Without this, anything unmapped reaches the servlet error page and answers in Boot's default JSON,
     * breaking the problem+json contract. The cause is logged rather than returned: a client has no use
     * for our internals.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpectedFailure(Exception e) {
        log.error("Unhandled failure while serving a request", e);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "The request could not be completed.");
    }

    /**
     * Every framework-translated failure passes through here, which is the one place where the shape of
     * those responses can be aligned with the hand-written ones above: one title per status, and a field
     * map for bean-validation failures, whose default detail ("Invalid request content.") tells the client
     * nothing about which field was wrong.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response == null || !(response.getBody() instanceof ProblemDetail problem)) {
            return response;
        }
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            problem.setTitle("Invalid request");
        }
        if (ex instanceof MethodArgumentNotValidException invalid) {
            problem.setProperty("errors", fieldErrors(invalid));
        }
        return response;
    }

    private static Map<String, String> fieldErrors(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return errors;
    }
}

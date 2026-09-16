package com.mwilk.ledger.app.web;

import com.mwilk.ledger.core.IdempotencyKeyConflictException;
import com.mwilk.ledger.core.InvalidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain and validation failures to RFC 9457 problem responses. Framework exceptions (missing header,
 * malformed JSON, bean validation) are already translated by the superclass.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

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
}

package com.mwilk.ledger.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Single place that shapes RFC 9457 problem bodies, so every error response carries the same fields. */
final class Problems {

    private Problems() {
    }

    static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}

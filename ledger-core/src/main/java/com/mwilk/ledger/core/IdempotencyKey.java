package com.mwilk.ledger.core;

/** Client-chosen key that makes a transfer request safe to retry. */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("Idempotency key must not be blank");
        }
    }
}

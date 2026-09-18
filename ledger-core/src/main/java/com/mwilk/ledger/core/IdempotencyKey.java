package com.mwilk.ledger.core;

/**
 * Client-chosen key that makes a transfer request safe to retry.
 *
 * <p>Scoped to the client that chose it: two clients independently picking {@code "order-42"} must not
 * collide, and one client must never learn that another has used a key.
 */
public record IdempotencyKey(String clientId, String value) {

    public IdempotencyKey {
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidRequestException("Client id must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("Idempotency key must not be blank");
        }
    }
}

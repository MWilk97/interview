package com.mwilk.ledger.core;

/**
 * Client-chosen key that makes a transfer request safe to retry.
 *
 * <p>Scoped to the client that chose it: two clients independently picking {@code "order-42"} must not
 * collide. The scope is only as trustworthy as {@code clientId}; this service takes it from an
 * unauthenticated header, so it separates well-behaved clients rather than isolating hostile ones.
 *
 * <p>Both parts are bounded, because an implementation retains keys for a retention window: their
 * length is retained memory that the caller chooses.
 */
public record IdempotencyKey(String clientId, String value) {

    /** Generous next to any real key — a UUID is 36 characters — and small enough to bound what one caller can pin. */
    public static final int MAX_LENGTH = 255;

    public IdempotencyKey {
        requireBounded(clientId, "Client id");
        requireBounded(value, "Idempotency key");
    }

    private static void requireBounded(String part, String name) {
        if (part == null || part.isBlank()) {
            throw new InvalidRequestException(name + " must not be blank");
        }
        if (part.length() > MAX_LENGTH) {
            throw new InvalidRequestException(
                    name + " must not exceed " + MAX_LENGTH + " characters, was " + part.length());
        }
    }
}

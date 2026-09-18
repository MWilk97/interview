package com.mwilk.ledger.core;

/** An idempotency key was reused with a different request than the one it was first used with. */
public class IdempotencyKeyConflictException extends RuntimeException {

    /** Transient because {@link Throwable} is serializable and {@link IdempotencyKey} deliberately is not. */
    private final transient IdempotencyKey key;

    public IdempotencyKeyConflictException(IdempotencyKey key) {
        super("Idempotency key '" + key.value() + "' was already used with a different request");
        this.key = key;
    }

    public IdempotencyKey key() {
        return key;
    }
}

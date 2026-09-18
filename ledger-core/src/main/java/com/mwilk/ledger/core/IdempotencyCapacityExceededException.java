package com.mwilk.ledger.core;

/** The ledger is already holding as many idempotency keys as it is willing to keep. */
public class IdempotencyCapacityExceededException extends RuntimeException {

    public IdempotencyCapacityExceededException(int maxEntries) {
        super("Idempotency key store is full at " + maxEntries + " keys");
    }
}

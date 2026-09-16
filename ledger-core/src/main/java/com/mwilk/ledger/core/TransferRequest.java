package com.mwilk.ledger.core;

import java.util.Objects;

/**
 * A request to move {@code amount} from {@code from} to {@code to}.
 * Structural rules (distinct accounts, positive amount) are enforced here, so a request that can never
 * succeed is rejected before it reaches the ledger and is never recorded against an idempotency key.
 */
public record TransferRequest(AccountId from, AccountId to, Money amount) {

    public TransferRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        if (from.equals(to)) {
            throw new InvalidRequestException("Source and target accounts must differ");
        }
        if (amount.isZero()) {
            throw new InvalidRequestException("Transfer amount must be positive");
        }
    }
}

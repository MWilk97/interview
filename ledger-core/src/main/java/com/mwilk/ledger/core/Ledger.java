package com.mwilk.ledger.core;

import java.util.Optional;

/**
 * The ledger port. The HTTP layer depends only on this interface; a persistent storage engine can replace
 * the in-memory implementation without touching callers.
 *
 * <p>Contract every implementation must honour:
 * <ul>
 *   <li>A transfer is atomic: it debits one account and credits the other, or changes nothing.</li>
 *   <li>A balance never goes below zero.</li>
 *   <li>No lost updates under concurrent transfers touching the same accounts.</li>
 *   <li>Transfers are applied at most once per {@link IdempotencyKey}; a repeated key returns the original
 *       {@link TransferOutcome}, even if the repeat arrives while the first attempt is still in progress.</li>
 * </ul>
 */
public interface Ledger {

    AccountId openAccount(Money initialBalance);

    /** Empty when the account does not exist. */
    Optional<Money> balance(AccountId accountId);

    /**
     * Applies {@code request} once for {@code key}.
     *
     * <p>Keys are retained for a bounded window; a retry that arrives after its key has expired is treated
     * as a new request and executes again.
     *
     * @throws IdempotencyKeyConflictException if {@code key} was already used with a different request
     * @throws IdempotencyCapacityExceededException if the implementation cannot accept another key
     */
    TransferOutcome transfer(IdempotencyKey key, TransferRequest request);
}

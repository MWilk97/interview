package com.mwilk.ledger.core;

/**
 * Result of a transfer attempt. Business rejections are values rather than exceptions because they are
 * legitimate outcomes that must be remembered and replayed for a retried idempotency key.
 */
public sealed interface TransferOutcome {

    /** Both accounts were updated. */
    record Completed(TransferId transferId) implements TransferOutcome {
    }

    /** Nothing was changed: the source account did not hold enough funds at the time of the attempt. */
    record InsufficientFunds(AccountId account, Money available, Money requested) implements TransferOutcome {
    }

    /** Nothing was changed: crediting the target would push its balance past the representable maximum. */
    record BalanceLimitExceeded(AccountId account, Money current, Money requested) implements TransferOutcome {
    }

    /** Nothing was changed: one of the accounts does not exist. */
    record UnknownAccount(AccountId account) implements TransferOutcome {
    }
}

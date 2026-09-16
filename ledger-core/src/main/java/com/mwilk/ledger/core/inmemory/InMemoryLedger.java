package com.mwilk.ledger.core.inmemory;

import com.mwilk.ledger.core.AccountId;
import com.mwilk.ledger.core.IdempotencyKey;
import com.mwilk.ledger.core.Ledger;
import com.mwilk.ledger.core.Money;
import com.mwilk.ledger.core.TransferId;
import com.mwilk.ledger.core.TransferOutcome;
import com.mwilk.ledger.core.TransferOutcome.Completed;
import com.mwilk.ledger.core.TransferOutcome.InsufficientFunds;
import com.mwilk.ledger.core.TransferOutcome.UnknownAccount;
import com.mwilk.ledger.core.TransferRequest;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe ledger that keeps all state on the heap.
 *
 * <p>Concurrency model: every account has its own lock. A transfer takes the locks of both accounts involved,
 * always in ascending {@link AccountId} order, so two transfers in opposite directions cannot deadlock and
 * transfers on disjoint accounts never contend. There is no global lock. Idempotency is handled by
 * {@link IdempotencyGuard} in front of the transfer logic.
 */
public final class InMemoryLedger implements Ledger {

    private final ConcurrentHashMap<AccountId, Account> accounts = new ConcurrentHashMap<>();
    private final IdempotencyGuard<TransferOutcome> transfers = new IdempotencyGuard<>();
    private final Runnable criticalSectionProbe;

    public InMemoryLedger() {
        this(() -> { });
    }

    /**
     * Test seam. {@code criticalSectionProbe} runs while both account locks are held, which lets tests hold a
     * transfer open and prove that unrelated transfers proceed and that overlapping retries do not re-execute.
     */
    InMemoryLedger(Runnable criticalSectionProbe) {
        this.criticalSectionProbe = Objects.requireNonNull(criticalSectionProbe, "criticalSectionProbe");
    }

    @Override
    public AccountId openAccount(Money initialBalance) {
        AccountId id = AccountId.random();
        accounts.put(id, new Account(initialBalance));
        return id;
    }

    @Override
    public Optional<Money> balance(AccountId accountId) {
        return Optional.ofNullable(accounts.get(accountId)).map(Account::balance);
    }

    @Override
    public TransferOutcome transfer(IdempotencyKey key, TransferRequest request) {
        return transfers.executeOnce(key, request, () -> apply(request));
    }

    private TransferOutcome apply(TransferRequest request) {
        Account source = accounts.get(request.from());
        if (source == null) {
            return new UnknownAccount(request.from());
        }
        Account target = accounts.get(request.to());
        if (target == null) {
            return new UnknownAccount(request.to());
        }

        boolean sourceFirst = request.from().compareTo(request.to()) < 0;
        Account first = sourceFirst ? source : target;
        Account second = sourceFirst ? target : source;

        first.lock().lock();
        try {
            second.lock().lock();
            try {
                criticalSectionProbe.run();
                return applyLocked(source, target, request);
            } finally {
                second.lock().unlock();
            }
        } finally {
            first.lock().unlock();
        }
    }

    private static TransferOutcome applyLocked(Account source, Account target, TransferRequest request) {
        Money available = source.balance();
        if (available.isLessThan(request.amount())) {
            return new InsufficientFunds(request.from(), available, request.amount());
        }
        // Compute both new balances before writing either, so an overflow on the credit side leaves nothing changed.
        Money debited = available.minus(request.amount());
        Money credited = target.balance().plus(request.amount());
        source.setBalance(debited);
        target.setBalance(credited);
        return new Completed(TransferId.random());
    }
}

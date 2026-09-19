package com.mwilk.ledger.core.inmemory;

import com.mwilk.ledger.core.AccountId;
import com.mwilk.ledger.core.IdempotencyKey;
import com.mwilk.ledger.core.Money;
import com.mwilk.ledger.core.TransferOutcome;
import com.mwilk.ledger.core.TransferOutcome.Completed;
import com.mwilk.ledger.core.TransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the concurrency guarantees: no lost updates, no double-spends, no deadlocks, parallelism for
 * unrelated accounts and at-most-once execution for overlapping retries.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class InMemoryLedgerConcurrencyTest {

    @Test
    void heavyContendedTransfersLoseNoUpdatesAndNeverOverdraw() throws Exception {
        int accountCount = 8;
        int threads = 16;
        int transfersPerThread = 5_000;
        long initialBalance = 1_000;

        InMemoryLedger ledger = new InMemoryLedger();
        List<AccountId> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            accounts.add(ledger.openAccount(Money.ofMinorUnits(initialBalance)));
        }
        // Expected net change per account, derived only from transfers the ledger reported as completed.
        AtomicLong[] expectedDelta = new AtomicLong[accountCount];
        for (int i = 0; i < accountCount; i++) {
            expectedDelta[i] = new AtomicLong();
        }
        LongAdder completed = new LongAdder();
        CyclicBarrier startTogether = new CyclicBarrier(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(pool.submit(() -> {
                    startTogether.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < transfersPerThread; i++) {
                        int from = random.nextInt(accountCount);
                        int to = (from + 1 + random.nextInt(accountCount - 1)) % accountCount;
                        long amount = 1 + random.nextInt(400);
                        TransferOutcome outcome = ledger.transfer(randomKey(),
                                new TransferRequest(accounts.get(from), accounts.get(to), Money.ofMinorUnits(amount)));
                        if (outcome instanceof Completed) {
                            expectedDelta[from].addAndGet(-amount);
                            expectedDelta[to].addAndGet(amount);
                            completed.increment();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> worker : workers) {
                worker.get();
            }
        }

        long total = 0;
        for (int i = 0; i < accountCount; i++) {
            long balance = ledger.balance(accounts.get(i)).orElseThrow().minorUnits();
            assertThat(balance).isGreaterThanOrEqualTo(0);
            assertThat(balance).isEqualTo(initialBalance + expectedDelta[i].get());
            total += balance;
        }
        assertThat(total).isEqualTo(accountCount * initialBalance);
        assertThat(completed.sum()).isGreaterThan(0);
    }

    @Test
    void onlyOneOfManyCompetingSpendsOfTheSameFundsSucceeds() throws Exception {
        int competitors = 64;
        InMemoryLedger ledger = new InMemoryLedger();
        AccountId source = ledger.openAccount(Money.ofMinorUnits(100));
        List<AccountId> targets = new ArrayList<>();
        for (int i = 0; i < competitors; i++) {
            targets.add(ledger.openAccount(Money.ZERO));
        }
        CyclicBarrier startTogether = new CyclicBarrier(competitors);

        List<Future<TransferOutcome>> attempts = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(competitors)) {
            for (AccountId target : targets) {
                attempts.add(pool.submit(() -> {
                    startTogether.await();
                    return ledger.transfer(randomKey(), new TransferRequest(source, target, Money.ofMinorUnits(100)));
                }));
            }
        }

        long completed = 0;
        for (Future<TransferOutcome> attempt : attempts) {
            if (attempt.get() instanceof Completed) {
                completed++;
            }
        }
        long targetTotal = targets.stream().mapToLong(t -> ledger.balance(t).orElseThrow().minorUnits()).sum();
        assertThat(completed).isEqualTo(1);
        assertThat(ledger.balance(source)).contains(Money.ZERO);
        assertThat(targetTotal).isEqualTo(100);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void oppositeDirectionTransfersOnTheSamePairDoNotDeadlock() throws Exception {
        int threads = 8;
        int transfersPerThread = 10_000;
        InMemoryLedger ledger = new InMemoryLedger();
        // Enough to fund every transfer even if one direction runs to completion first, so a rejection here
        // would mean a real defect rather than a scheduling accident.
        AccountId a = ledger.openAccount(Money.ofMinorUnits(100_000));
        AccountId b = ledger.openAccount(Money.ofMinorUnits(100_000));
        LongAdder completed = new LongAdder();
        CyclicBarrier startTogether = new CyclicBarrier(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                boolean aToB = t % 2 == 0;
                workers.add(pool.submit(() -> {
                    startTogether.await();
                    TransferRequest request = aToB
                            ? new TransferRequest(a, b, Money.ofMinorUnits(1))
                            : new TransferRequest(b, a, Money.ofMinorUnits(1));
                    for (int i = 0; i < transfersPerThread; i++) {
                        if (ledger.transfer(randomKey(), request) instanceof Completed) {
                            completed.increment();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> worker : workers) {
                worker.get();
            }
        }

        long total = ledger.balance(a).orElseThrow().minorUnits() + ledger.balance(b).orElseThrow().minorUnits();
        assertThat(total).isEqualTo(200_000);
        assertThat(completed.sum()).isEqualTo((long) threads * transfersPerThread);
    }

    @Test
    void transferOnUnrelatedAccountsCompletesWhileAnotherTransferHoldsItsLocks() throws Exception {
        HoldingProbe probe = new HoldingProbe();
        InMemoryLedger ledger = new InMemoryLedger(probe);
        AccountId a = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId b = ledger.openAccount(Money.ZERO);
        AccountId c = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId d = ledger.openAccount(Money.ZERO);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<TransferOutcome> held = pool.submit(
                    () -> ledger.transfer(randomKey(), new TransferRequest(a, b, Money.ofMinorUnits(10))));
            probe.awaitEntered();

            Future<TransferOutcome> unrelated = pool.submit(
                    () -> ledger.transfer(randomKey(), new TransferRequest(c, d, Money.ofMinorUnits(10))));
            assertThat(unrelated.get(5, TimeUnit.SECONDS)).isInstanceOf(Completed.class);
            assertThat(held.isDone()).isFalse();

            probe.release();
            assertThat(held.get()).isInstanceOf(Completed.class);
        }
        assertThat(ledger.balance(b)).contains(Money.ofMinorUnits(10));
        assertThat(ledger.balance(d)).contains(Money.ofMinorUnits(10));
    }

    @Test
    void retriesArrivingWhileTheOriginalIsInFlightAreAppliedOnceAndSeeTheOriginalOutcome() throws Exception {
        int retries = 20;
        HoldingProbe probe = new HoldingProbe();
        InMemoryLedger ledger = new InMemoryLedger(probe);
        AccountId from = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId to = ledger.openAccount(Money.ZERO);
        IdempotencyKey key = new IdempotencyKey("client", "client-retry");
        TransferRequest request = new TransferRequest(from, to, Money.ofMinorUnits(30));

        List<Future<TransferOutcome>> attempts = new ArrayList<>();
        List<AtomicReference<Thread>> retryThreads = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(retries + 1)) {
            attempts.add(pool.submit(() -> ledger.transfer(key, request)));
            probe.awaitEntered();

            for (int i = 0; i < retries; i++) {
                AtomicReference<Thread> retryThread = new AtomicReference<>();
                retryThreads.add(retryThread);
                attempts.add(pool.submit(() -> {
                    retryThread.set(Thread.currentThread());
                    return ledger.transfer(key, request);
                }));
            }
            // Every retry has reached the guard and blocked, so "none is done" says something.
            for (AtomicReference<Thread> retryThread : retryThreads) {
                Threads.awaitParked(retryThread);
            }
            assertThat(attempts).noneMatch(Future::isDone);

            probe.release();
        }

        TransferOutcome original = attempts.get(0).get();
        assertThat(original).isInstanceOf(Completed.class);
        for (Future<TransferOutcome> attempt : attempts) {
            assertThat(attempt.get()).isEqualTo(original);
        }
        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(70));
        assertThat(ledger.balance(to)).contains(Money.ofMinorUnits(30));
    }

    private static IdempotencyKey randomKey() {
        return new IdempotencyKey("client", UUID.randomUUID().toString());
    }
}

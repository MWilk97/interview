package com.mwilk.ledger.core.inmemory;

import com.mwilk.ledger.core.IdempotencyCapacityExceededException;
import com.mwilk.ledger.core.IdempotencyKey;
import com.mwilk.ledger.core.IdempotencyKeyConflictException;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runs an action at most once per key and replays its result to every later (or concurrent) caller.
 *
 * <p>The first caller to claim a key owns the execution; anyone else arriving with the same key waits on the
 * owner's future, so a retry that overlaps with the original request observes the original outcome instead of
 * running the action again. If the owner fails with an exception the key is released, because the action is
 * required to be atomic and therefore nothing was applied.
 *
 * <p>Keys are kept for a bounded retention window and the store has a hard cap, so an endless stream of fresh
 * keys cannot exhaust the heap. <strong>A retry that arrives after its key has expired re-executes the
 * action.</strong> That is the deliberate trade-off every idempotency store makes; the window has to outlast
 * any client's retry schedule.
 *
 * @param <R> the memoised result type
 */
final class IdempotencyGuard<R> {

    static final Duration DEFAULT_RETENTION = Duration.ofHours(24);
    static final int DEFAULT_MAX_ENTRIES = 1_000_000;

    /** A sweep is O(size), so it is amortised over this many claims. */
    private static final int SWEEP_INTERVAL = 1_024;

    private record Entry<R>(Object fingerprint, CompletableFuture<R> result, long claimedAt) {
    }

    private final ConcurrentHashMap<IdempotencyKey, Entry<R>> entries = new ConcurrentHashMap<>();
    private final AtomicLong claims = new AtomicLong();
    private final long retentionNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;

    IdempotencyGuard() {
        this(DEFAULT_RETENTION, DEFAULT_MAX_ENTRIES, System::nanoTime);
    }

    /** @param nanoClock a {@link System#nanoTime()}-like source; injected so expiry can be tested without sleeping */
    IdempotencyGuard(Duration retention, int maxEntries, LongSupplier nanoClock) {
        Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Retention must be positive: " + retention);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("Max entries must be positive: " + maxEntries);
        }
        this.retentionNanos = retention.toNanos();
        this.maxEntries = maxEntries;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * @param fingerprint identifies the request; reusing {@code key} with a different fingerprint is a conflict
     * @throws IdempotencyKeyConflictException if {@code key} is bound to a different fingerprint
     * @throws IdempotencyCapacityExceededException if the store is full
     */
    R executeOnce(IdempotencyKey key, Object fingerprint, Supplier<R> action) {
        while (true) {
            Entry<R> mine = new Entry<>(fingerprint, new CompletableFuture<>(), nanoClock.getAsLong());
            Entry<R> existing = entries.putIfAbsent(key, mine);
            if (existing == null) {
                claimed(key, mine);
                return runAsOwner(key, mine, action);
            }
            if (isExpired(existing)) {
                if (!entries.replace(key, existing, mine)) {
                    continue;
                }
                claimed(key, mine);
                return runAsOwner(key, mine, action);
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyConflictException(key);
            }
            try {
                return existing.result().join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException cause ? cause : e;
            }
        }
    }

    /** An entry that is still running has waiters on its future and must outlive its retention window. */
    private boolean isExpired(Entry<R> entry) {
        return entry.result().isDone() && entry.claimedAt() - (nanoClock.getAsLong() - retentionNanos) <= 0;
    }

    private void claimed(IdempotencyKey key, Entry<R> mine) {
        if (claims.incrementAndGet() % SWEEP_INTERVAL == 0 || entries.size() > maxEntries) {
            sweep();
        }
        if (entries.size() > maxEntries) {
            entries.remove(key, mine);
            IdempotencyCapacityExceededException failure = new IdempotencyCapacityExceededException(maxEntries);
            // A caller may already be waiting on this future; it must not be abandoned uncompleted.
            mine.result().completeExceptionally(failure);
            throw failure;
        }
    }

    private void sweep() {
        for (Map.Entry<IdempotencyKey, Entry<R>> candidate : entries.entrySet()) {
            Entry<R> entry = candidate.getValue();
            if (isExpired(entry)) {
                entries.remove(candidate.getKey(), entry);
            }
        }
    }

    private R runAsOwner(IdempotencyKey key, Entry<R> mine, Supplier<R> action) {
        try {
            R result = action.get();
            mine.result().complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            entries.remove(key, mine);
            mine.result().completeExceptionally(e);
            throw e;
        }
    }
}

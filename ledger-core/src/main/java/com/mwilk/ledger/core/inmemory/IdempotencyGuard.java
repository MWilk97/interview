package com.mwilk.ledger.core.inmemory;

import com.mwilk.ledger.core.IdempotencyKey;
import com.mwilk.ledger.core.IdempotencyKeyConflictException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs an action at most once per key and replays its result to every later (or concurrent) caller.
 *
 * <p>The first caller to claim a key owns the execution; anyone else arriving with the same key waits on the
 * owner's future, so a retry that overlaps with the original request observes the original outcome instead of
 * running the action again. If the owner fails with an exception the key is released, because the action is
 * required to be atomic and therefore nothing was applied.
 *
 * @param <R> the memoised result type
 */
final class IdempotencyGuard<R> {

    private record Entry<R>(Object fingerprint, CompletableFuture<R> result) {
    }

    private final ConcurrentHashMap<IdempotencyKey, Entry<R>> entries = new ConcurrentHashMap<>();

    /**
     * @param fingerprint identifies the request; reusing {@code key} with a different fingerprint is a conflict
     * @throws IdempotencyKeyConflictException if {@code key} is bound to a different fingerprint
     */
    R executeOnce(IdempotencyKey key, Object fingerprint, Supplier<R> action) {
        Entry<R> mine = new Entry<>(fingerprint, new CompletableFuture<>());
        Entry<R> existing = entries.putIfAbsent(key, mine);
        if (existing == null) {
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

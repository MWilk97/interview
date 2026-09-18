package com.mwilk.ledger.core.inmemory;

import com.mwilk.ledger.core.IdempotencyCapacityExceededException;
import com.mwilk.ledger.core.IdempotencyKey;
import com.mwilk.ledger.core.IdempotencyKeyConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class IdempotencyGuardTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("client", "key");

    private final IdempotencyGuard<String> guard = new IdempotencyGuard<>();

    @Test
    void runsActionOnceAndReplaysItsResult() {
        AtomicInteger invocations = new AtomicInteger();

        String first = guard.executeOnce(KEY, "req", () -> "result-" + invocations.incrementAndGet());
        String second = guard.executeOnce(KEY, "req", () -> "result-" + invocations.incrementAndGet());

        assertThat(first).isEqualTo("result-1");
        assertThat(second).isEqualTo("result-1");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void sameKeyWithDifferentFingerprintIsAConflict() {
        guard.executeOnce(KEY, "req-a", () -> "a");

        assertThatThrownBy(() -> guard.executeOnce(KEY, "req-b", () -> "b"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void callersArrivingWhileOwnerIsRunningWaitForTheOwnersResult() throws Exception {
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(21)) {
            Future<String> owner = pool.submit(() -> guard.executeOnce(KEY, "req", () -> {
                invocations.incrementAndGet();
                ownerStarted.countDown();
                await(releaseOwner);
                return "owner-result";
            }));
            ownerStarted.await();

            List<Future<String>> retries = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                retries.add(pool.submit(() -> guard.executeOnce(KEY, "req", () -> {
                    invocations.incrementAndGet();
                    return "retry-result";
                })));
            }
            assertThat(retries).noneMatch(Future::isDone);

            releaseOwner.countDown();

            assertThat(owner.get()).isEqualTo("owner-result");
            for (Future<String> retry : retries) {
                assertThat(retry.get()).isEqualTo("owner-result");
            }
        }
        assertThat(invocations).hasValue(1);
    }

    @Test
    void concurrentCallerWithDifferentFingerprintIsRejectedWhileOwnerIsStillRunning() throws Exception {
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> guard.executeOnce(KEY, "req-a", () -> {
                ownerStarted.countDown();
                await(releaseOwner);
                return "a";
            }));
            ownerStarted.await();

            assertThatThrownBy(() -> guard.executeOnce(KEY, "req-b", () -> "b"))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
            releaseOwner.countDown();
        }
    }

    @Test
    void failedOwnerReleasesTheKeyAndPropagatesTheFailureToWaiters() throws Exception {
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> owner = pool.submit(() -> guard.executeOnce(KEY, "req", () -> {
                ownerStarted.countDown();
                await(releaseOwner);
                throw new IllegalStateException("storage unavailable");
            }));
            ownerStarted.await();
            AtomicReference<Thread> waiterThread = new AtomicReference<>();
            Future<String> waiter = pool.submit(() -> {
                waiterThread.set(Thread.currentThread());
                return guard.executeOnce(KEY, "req", () -> "should not run yet");
            });
            Threads.awaitParked(waiterThread);
            releaseOwner.countDown();

            assertThatThrownBy(owner::get).cause().isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(waiter::get).cause().isInstanceOf(IllegalStateException.class);
        }

        assertThat(guard.executeOnce(KEY, "req", () -> "fresh attempt")).isEqualTo("fresh attempt");
    }

    @Test
    void aCompletedEntryExpiresAfterItsRetentionWindow() {
        AtomicLong now = new AtomicLong();
        IdempotencyGuard<String> expiring = new IdempotencyGuard<>(Duration.ofHours(1), 100, now::get);
        AtomicInteger invocations = new AtomicInteger();

        String first = expiring.executeOnce(KEY, "req", () -> "result-" + invocations.incrementAndGet());
        String replayed = expiring.executeOnce(KEY, "req", () -> "result-" + invocations.incrementAndGet());
        now.addAndGet(Duration.ofHours(2).toNanos());
        String afterExpiry = expiring.executeOnce(KEY, "req", () -> "result-" + invocations.incrementAndGet());

        assertThat(first).isEqualTo("result-1");
        assertThat(replayed).isEqualTo("result-1");
        assertThat(afterExpiry).isEqualTo("result-2");
    }

    @Test
    void anEntryStillInFlightIsNeverExpired() throws Exception {
        AtomicLong now = new AtomicLong();
        IdempotencyGuard<String> expiring = new IdempotencyGuard<>(Duration.ofHours(1), 100, now::get);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> owner = pool.submit(() -> expiring.executeOnce(KEY, "req", () -> {
                invocations.incrementAndGet();
                ownerStarted.countDown();
                await(releaseOwner);
                return "owner-result";
            }));
            ownerStarted.await();
            now.addAndGet(Duration.ofDays(1).toNanos());

            AtomicReference<Thread> waiterThread = new AtomicReference<>();
            Future<String> retry = pool.submit(() -> {
                waiterThread.set(Thread.currentThread());
                return expiring.executeOnce(KEY, "req", () -> {
                    invocations.incrementAndGet();
                    return "retry-result";
                });
            });
            Threads.awaitParked(waiterThread);
            releaseOwner.countDown();

            assertThat(owner.get()).isEqualTo("owner-result");
            assertThat(retry.get()).isEqualTo("owner-result");
        }
        assertThat(invocations).hasValue(1);
    }

    @Test
    void theStoreRefusesToGrowPastItsCap() {
        IdempotencyGuard<String> tiny = new IdempotencyGuard<>(Duration.ofHours(1), 2, System::nanoTime);

        tiny.executeOnce(new IdempotencyKey("client", "k1"), "req", () -> "a");
        tiny.executeOnce(new IdempotencyKey("client", "k2"), "req", () -> "b");

        assertThatThrownBy(() -> tiny.executeOnce(new IdempotencyKey("client", "k3"), "req", () -> "c"))
                .isInstanceOf(IdempotencyCapacityExceededException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

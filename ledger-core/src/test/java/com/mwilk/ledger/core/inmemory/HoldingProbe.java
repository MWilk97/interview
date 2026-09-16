package com.mwilk.ledger.core.inmemory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Critical-section probe that blocks the first transfer that reaches it until {@link #release()} is called.
 * Every later transfer passes straight through.
 */
final class HoldingProbe implements Runnable {

    private final AtomicBoolean armed = new AtomicBoolean(true);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public void run() {
        if (!armed.compareAndSet(true, false)) {
            return;
        }
        entered.countDown();
        try {
            if (!released.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Probe was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding the critical section", e);
        }
    }

    void awaitEntered() throws InterruptedException {
        if (!entered.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("No transfer reached the critical section");
        }
    }

    void release() {
        released.countDown();
    }
}

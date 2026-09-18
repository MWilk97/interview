package com.mwilk.ledger.core.inmemory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test helper that waits until a worker is genuinely blocked, so a test cannot race ahead of it. */
final class Threads {

    private Threads() {
    }

    /** Spins until the referenced thread is parked, e.g. inside the guard waiting on the owner's result. */
    static void awaitParked(AtomicReference<Thread> threadRef) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread thread = threadRef.get();
            if (thread != null && thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Thread never blocked");
    }
}

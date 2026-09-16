package com.mwilk.ledger.core.inmemory;

import com.mwilk.ledger.core.Money;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutable account state. The balance is written only while {@link #lock()} is held; reads are lock-free
 * because {@link Money} is immutable and the reference is volatile, so a reader always sees a complete value.
 */
final class Account {

    private final ReentrantLock lock = new ReentrantLock();
    private volatile Money balance;

    Account(Money initialBalance) {
        this.balance = Objects.requireNonNull(initialBalance, "initialBalance");
    }

    ReentrantLock lock() {
        return lock;
    }

    Money balance() {
        return balance;
    }

    void setBalance(Money newBalance) {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Account lock must be held to change the balance");
        }
        balance = newBalance;
    }
}

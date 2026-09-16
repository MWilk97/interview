package com.mwilk.ledger.core;

import java.util.Objects;
import java.util.UUID;

/** Identifies an account. Ordered so that implementations can acquire per-account locks in a global order. */
public record AccountId(UUID value) implements Comparable<AccountId> {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId random() {
        return new AccountId(UUID.randomUUID());
    }

    /** @throws InvalidRequestException if {@code text} is not a UUID */
    public static AccountId parse(String text) {
        try {
            return new AccountId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Malformed account id: " + text, e);
        }
    }

    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

package com.mwilk.ledger.core;

/**
 * A non-negative amount in minor units (e.g. cents) of a single implicit currency.
 * Arithmetic is exact: overflow raises {@link ArithmeticException} instead of wrapping.
 */
public record Money(long minorUnits) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (minorUnits < 0) {
            throw new InvalidRequestException("Money must not be negative: " + minorUnits);
        }
    }

    public static Money ofMinorUnits(long minorUnits) {
        return new Money(minorUnits);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minorUnits, other.minorUnits));
    }

    /**
     * Whether {@link #plus} would stay representable. Both operands are non-negative, so the subtraction
     * on the right cannot itself overflow.
     */
    public boolean canAdd(Money other) {
        return other.minorUnits <= Long.MAX_VALUE - minorUnits;
    }

    /** @throws InvalidRequestException if the result would be negative */
    public Money minus(Money other) {
        return new Money(Math.subtractExact(minorUnits, other.minorUnits));
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isLessThan(Money other) {
        return minorUnits < other.minorUnits;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(minorUnits, other.minorUnits);
    }
}

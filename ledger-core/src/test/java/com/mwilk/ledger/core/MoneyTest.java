package com.mwilk.ledger.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.ofMinorUnits(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsAndSubtractsExactly() {
        Money hundred = Money.ofMinorUnits(100);
        assertThat(hundred.plus(Money.ofMinorUnits(50))).isEqualTo(Money.ofMinorUnits(150));
        assertThat(hundred.minus(Money.ofMinorUnits(100))).isEqualTo(Money.ZERO);
    }

    @Test
    void subtractionBelowZeroIsRejected() {
        assertThatThrownBy(() -> Money.ofMinorUnits(1).minus(Money.ofMinorUnits(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void additionOverflowFailsInsteadOfWrapping() {
        assertThatThrownBy(() -> Money.ofMinorUnits(Long.MAX_VALUE).plus(Money.ofMinorUnits(1)))
                .isInstanceOf(ArithmeticException.class);
    }
}

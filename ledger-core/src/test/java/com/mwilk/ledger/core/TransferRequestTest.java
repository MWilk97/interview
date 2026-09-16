package com.mwilk.ledger.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferRequestTest {

    @Test
    void rejectsTransferToTheSameAccount() {
        AccountId account = AccountId.random();
        assertThatThrownBy(() -> new TransferRequest(account, account, Money.ofMinorUnits(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> new TransferRequest(AccountId.random(), AccountId.random(), Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}

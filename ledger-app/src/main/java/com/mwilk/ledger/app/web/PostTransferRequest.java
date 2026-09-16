package com.mwilk.ledger.app.web;

import com.mwilk.ledger.core.AccountId;
import com.mwilk.ledger.core.Money;
import com.mwilk.ledger.core.TransferRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** @param amount amount to move in minor units */
public record PostTransferRequest(@NotBlank String fromAccountId,
                                  @NotBlank String toAccountId,
                                  @NotNull @Positive Long amount) {

    TransferRequest toDomain() {
        return new TransferRequest(AccountId.parse(fromAccountId), AccountId.parse(toAccountId), Money.ofMinorUnits(amount));
    }
}

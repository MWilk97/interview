package com.mwilk.ledger.app.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** @param initialBalance opening balance in minor units */
record OpenAccountRequest(@NotNull @PositiveOrZero Long initialBalance) {
}

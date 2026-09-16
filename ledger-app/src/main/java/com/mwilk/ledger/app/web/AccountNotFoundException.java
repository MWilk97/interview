package com.mwilk.ledger.app.web;

import com.mwilk.ledger.core.AccountId;

class AccountNotFoundException extends RuntimeException {

    AccountNotFoundException(AccountId accountId) {
        super("Account " + accountId + " does not exist");
    }
}

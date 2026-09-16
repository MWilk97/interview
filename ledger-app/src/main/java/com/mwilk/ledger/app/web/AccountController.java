package com.mwilk.ledger.app.web;

import com.mwilk.ledger.core.AccountId;
import com.mwilk.ledger.core.Ledger;
import com.mwilk.ledger.core.Money;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/accounts")
class AccountController {

    private final Ledger ledger;

    AccountController(Ledger ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Money initialBalance = Money.ofMinorUnits(request.initialBalance());
        AccountId id = ledger.openAccount(initialBalance);
        return ResponseEntity
                .created(URI.create("/accounts/" + id))
                .body(new AccountResponse(id.toString(), initialBalance.minorUnits()));
    }

    @GetMapping("/{id}")
    AccountResponse balance(@PathVariable String id) {
        AccountId accountId = AccountId.parse(id);
        Money balance = ledger.balance(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        return new AccountResponse(accountId.toString(), balance.minorUnits());
    }
}

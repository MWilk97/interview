package com.mwilk.ledger.app.web;

import com.mwilk.ledger.core.IdempotencyKey;
import com.mwilk.ledger.core.Ledger;
import com.mwilk.ledger.core.TransferOutcome;
import com.mwilk.ledger.core.TransferOutcome.BalanceLimitExceeded;
import com.mwilk.ledger.core.TransferOutcome.Completed;
import com.mwilk.ledger.core.TransferOutcome.InsufficientFunds;
import com.mwilk.ledger.core.TransferOutcome.UnknownAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransferController {

    private final Ledger ledger;

    TransferController(Ledger ledger) {
        this.ledger = ledger;
    }

    /**
     * The mapping from outcome to response is a pure function, so a replayed outcome yields an identical
     * response for a retried idempotency key.
     */
    @PostMapping("/transfers")
    ResponseEntity<Object> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody PostTransferRequest request) {
        TransferOutcome outcome = ledger.transfer(new IdempotencyKey(idempotencyKey), request.toDomain());
        return switch (outcome) {
            case Completed completed -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(new TransferResponse(completed.transferId().toString()));
            case InsufficientFunds rejected -> ResponseEntity
                    .of(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds",
                            "Account %s has %d available, %d requested".formatted(rejected.account(),
                                    rejected.available().minorUnits(), rejected.requested().minorUnits())))
                    .build();
            case BalanceLimitExceeded rejected -> ResponseEntity
                    .of(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "Balance limit exceeded",
                            "Account %s holds %d, crediting %d more would exceed the maximum balance"
                                    .formatted(rejected.account(), rejected.current().minorUnits(),
                                            rejected.requested().minorUnits())))
                    .build();
            case UnknownAccount rejected -> throw new AccountNotFoundException(rejected.account());
        };
    }
}

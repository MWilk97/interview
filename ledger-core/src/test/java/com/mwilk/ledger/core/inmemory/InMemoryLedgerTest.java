package com.mwilk.ledger.core.inmemory;

import com.mwilk.ledger.core.AccountId;
import com.mwilk.ledger.core.IdempotencyKey;
import com.mwilk.ledger.core.IdempotencyKeyConflictException;
import com.mwilk.ledger.core.Money;
import com.mwilk.ledger.core.TransferOutcome;
import com.mwilk.ledger.core.TransferOutcome.BalanceLimitExceeded;
import com.mwilk.ledger.core.TransferOutcome.Completed;
import com.mwilk.ledger.core.TransferOutcome.InsufficientFunds;
import com.mwilk.ledger.core.TransferOutcome.UnknownAccount;
import com.mwilk.ledger.core.TransferRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryLedgerTest {

    private final InMemoryLedger ledger = new InMemoryLedger();

    @Test
    void openedAccountStartsWithItsInitialBalance() {
        AccountId id = ledger.openAccount(Money.ofMinorUnits(500));

        assertThat(ledger.balance(id)).contains(Money.ofMinorUnits(500));
    }

    @Test
    void balanceOfUnknownAccountIsEmpty() {
        assertThat(ledger.balance(AccountId.random())).isEmpty();
    }

    @Test
    void completedTransferDebitsSourceAndCreditsTarget() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId to = ledger.openAccount(Money.ZERO);

        TransferOutcome outcome = ledger.transfer(key("t1"), request(from, to, 60));

        assertThat(outcome).isInstanceOf(Completed.class);
        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(40));
        assertThat(ledger.balance(to)).contains(Money.ofMinorUnits(60));
    }

    @Test
    void transferOfTheEntireBalanceIsAllowed() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId to = ledger.openAccount(Money.ZERO);

        assertThat(ledger.transfer(key("t1"), request(from, to, 100))).isInstanceOf(Completed.class);
        assertThat(ledger.balance(from)).contains(Money.ZERO);
    }

    @Test
    void insufficientFundsChangesNothing() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(30));
        AccountId to = ledger.openAccount(Money.ofMinorUnits(10));

        TransferOutcome outcome = ledger.transfer(key("t1"), request(from, to, 31));

        assertThat(outcome).isEqualTo(new InsufficientFunds(from, Money.ofMinorUnits(30), Money.ofMinorUnits(31)));
        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(30));
        assertThat(ledger.balance(to)).contains(Money.ofMinorUnits(10));
    }

    @Test
    void unknownSourceOrTargetIsReportedAndChangesNothing() {
        AccountId known = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId missing = AccountId.random();

        assertThat(ledger.transfer(key("t1"), request(missing, known, 10))).isEqualTo(new UnknownAccount(missing));
        assertThat(ledger.transfer(key("t2"), request(known, missing, 10))).isEqualTo(new UnknownAccount(missing));
        assertThat(ledger.balance(known)).contains(Money.ofMinorUnits(100));
    }

    @Test
    void repeatedKeyReturnsOriginalOutcomeAndAppliesTransferOnce() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId to = ledger.openAccount(Money.ZERO);
        TransferRequest request = request(from, to, 40);

        TransferOutcome first = ledger.transfer(key("retry-me"), request);
        TransferOutcome second = ledger.transfer(key("retry-me"), request);

        assertThat(second).isEqualTo(first);
        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(60));
        assertThat(ledger.balance(to)).contains(Money.ofMinorUnits(40));
    }

    @Test
    void repeatedKeyReplaysRejectionEvenAfterFundsBecomeAvailable() {
        AccountId from = ledger.openAccount(Money.ZERO);
        AccountId to = ledger.openAccount(Money.ZERO);
        AccountId funder = ledger.openAccount(Money.ofMinorUnits(1_000));
        TransferRequest request = request(from, to, 40);

        TransferOutcome rejected = ledger.transfer(key("k"), request);
        ledger.transfer(key("top-up"), request(funder, from, 100));
        TransferOutcome replayed = ledger.transfer(key("k"), request);

        assertThat(rejected).isInstanceOf(InsufficientFunds.class);
        assertThat(replayed).isEqualTo(rejected);
        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(100));
        assertThat(ledger.balance(to)).contains(Money.ZERO);
    }

    @Test
    void reusingKeyWithDifferentRequestIsAConflictAndChangesNothing() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId to = ledger.openAccount(Money.ZERO);
        ledger.transfer(key("k"), request(from, to, 10));

        assertThatThrownBy(() -> ledger.transfer(key("k"), request(from, to, 20)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(90));
    }

    @Test
    void creditOverflowIsRejectedWithoutTouchingEitherAccount() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(10));
        AccountId to = ledger.openAccount(Money.ofMinorUnits(Long.MAX_VALUE));

        TransferOutcome outcome = ledger.transfer(key("k"), request(from, to, 1));

        assertThat(outcome)
                .isEqualTo(new BalanceLimitExceeded(to, Money.ofMinorUnits(Long.MAX_VALUE), Money.ofMinorUnits(1)));
        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(10));
        assertThat(ledger.balance(to)).contains(Money.ofMinorUnits(Long.MAX_VALUE));
    }

    @Test
    void repeatedKeyReplaysABalanceLimitRejectionEvenAfterRoomIsFreed() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(10));
        AccountId to = ledger.openAccount(Money.ofMinorUnits(Long.MAX_VALUE));
        AccountId drain = ledger.openAccount(Money.ZERO);

        TransferOutcome rejected = ledger.transfer(key("k"), request(from, to, 1));
        ledger.transfer(key("drain"), request(to, drain, 1_000));
        TransferOutcome replayed = ledger.transfer(key("k"), request(from, to, 1));

        assertThat(rejected).isInstanceOf(BalanceLimitExceeded.class);
        assertThat(replayed).isEqualTo(rejected);
        assertThat(ledger.balance(to)).contains(Money.ofMinorUnits(Long.MAX_VALUE - 1_000));
    }

    @Test
    void sameKeyValueFromDifferentClientsDoesNotCollide() {
        AccountId from = ledger.openAccount(Money.ofMinorUnits(100));
        AccountId to = ledger.openAccount(Money.ZERO);

        ledger.transfer(new IdempotencyKey("client-a", "order-42"), request(from, to, 10));
        ledger.transfer(new IdempotencyKey("client-b", "order-42"), request(from, to, 10));

        assertThat(ledger.balance(from)).contains(Money.ofMinorUnits(80));
        assertThat(ledger.balance(to)).contains(Money.ofMinorUnits(20));
    }

    private static IdempotencyKey key(String value) {
        return new IdempotencyKey("client", value);
    }

    private static TransferRequest request(AccountId from, AccountId to, long amount) {
        return new TransferRequest(from, to, Money.ofMinorUnits(amount));
    }
}

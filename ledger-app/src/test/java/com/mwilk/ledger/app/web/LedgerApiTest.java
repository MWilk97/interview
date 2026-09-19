package com.mwilk.ledger.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class LedgerApiTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String CLIENT_ID = "X-Client-Id";
    private static final String CLIENT = "client-a";

    @Autowired
    private RestTestClient client;

    @Test
    void createsAccountAndReadsItsBalance() {
        AccountResponse created = openAccount(250);

        client.get().uri("/accounts/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(AccountResponse.class)
                .isEqualTo(new AccountResponse(created.id(), 250));
    }

    @Test
    void createAccountReturnsLocationHeader() {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OpenAccountRequest(0L))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueMatches("Location", "/accounts/[0-9a-f-]{36}");
    }

    @Test
    void rejectsNegativeInitialBalanceAndNamesTheField() {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OpenAccountRequest(-1L))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Invalid request")
                .jsonPath("$.errors.initialBalance").exists();
    }

    @Test
    void unknownAccountIsNotFoundProblem() {
        client.get().uri("/accounts/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.title").isEqualTo("Unknown account");
    }

    @Test
    void malformedAccountIdIsBadRequest() {
        client.get().uri("/accounts/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);

        TransferResponse response = client.post().uri("/transfers")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .header(CLIENT_ID, CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PostTransferRequest(from.id(), to.id(), 60L))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TransferResponse.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.transferId()).isNotBlank();
        assertThat(balanceOf(from.id())).isEqualTo(40);
        assertThat(balanceOf(to.id())).isEqualTo(60);
    }

    @Test
    void insufficientFundsIsUnprocessableAndChangesNothing() {
        AccountResponse from = openAccount(10);
        AccountResponse to = openAccount(0);

        client.post().uri("/transfers")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .header(CLIENT_ID, CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PostTransferRequest(from.id(), to.id(), 11L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody().jsonPath("$.title").isEqualTo("Insufficient funds");

        assertThat(balanceOf(from.id())).isEqualTo(10);
        assertThat(balanceOf(to.id())).isEqualTo(0);
    }

    @Test
    void creditOverflowIsUnprocessableAndChangesNothing() {
        AccountResponse from = openAccount(10);
        AccountResponse to = openAccount(Long.MAX_VALUE);

        postTransfer(UUID.randomUUID().toString(), new PostTransferRequest(from.id(), to.id(), 1L))
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.title").isEqualTo("Balance limit exceeded");

        assertThat(balanceOf(from.id())).isEqualTo(10);
        assertThat(balanceOf(to.id())).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void transferToUnknownAccountIsNotFound() {
        AccountResponse from = openAccount(10);

        client.post().uri("/transfers")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .header(CLIENT_ID, CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PostTransferRequest(from.id(), UUID.randomUUID().toString(), 5L))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void repeatedIdempotencyKeyReplaysOriginalResponseWithoutApplyingTwice() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);
        String key = UUID.randomUUID().toString();
        PostTransferRequest body = new PostTransferRequest(from.id(), to.id(), 30L);

        TransferResponse first = postTransfer(key, body).expectStatus().isCreated()
                .expectBody(TransferResponse.class).returnResult().getResponseBody();
        TransferResponse second = postTransfer(key, body).expectStatus().isCreated()
                .expectBody(TransferResponse.class).returnResult().getResponseBody();

        assertThat(second).isEqualTo(first);
        assertThat(balanceOf(from.id())).isEqualTo(70);
        assertThat(balanceOf(to.id())).isEqualTo(30);
    }

    @Test
    void repeatedIdempotencyKeyReplaysRejectionToo() {
        AccountResponse from = openAccount(0);
        AccountResponse to = openAccount(0);
        String key = UUID.randomUUID().toString();
        PostTransferRequest body = new PostTransferRequest(from.id(), to.id(), 30L);

        postTransfer(key, body).expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        postTransfer(key, body).expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void reusingIdempotencyKeyWithDifferentBodyIsConflict() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);
        String key = UUID.randomUUID().toString();

        postTransfer(key, new PostTransferRequest(from.id(), to.id(), 10L)).expectStatus().isCreated();
        postTransfer(key, new PostTransferRequest(from.id(), to.id(), 20L))
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Idempotency key conflict")
                .jsonPath("$.idempotencyKey").isEqualTo(key);

        assertThat(balanceOf(from.id())).isEqualTo(90);
    }

    @Test
    void theSameKeyFromTwoClientsIsNotAConflict() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);
        PostTransferRequest body = new PostTransferRequest(from.id(), to.id(), 10L);

        postTransfer("client-a", "order-42", body).expectStatus().isCreated();
        postTransfer("client-b", "order-42", body).expectStatus().isCreated();

        assertThat(balanceOf(to.id())).isEqualTo(20);
    }

    @Test
    void missingClientIdIsBadRequest() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);

        client.post().uri("/transfers")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PostTransferRequest(from.id(), to.id(), 10L))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);

        client.post().uri("/transfers")
                .header(CLIENT_ID, CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PostTransferRequest(from.id(), to.id(), 10L))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.title").isEqualTo("Invalid request");
    }

    @Test
    void nonPositiveAmountAndSameAccountAreBadRequests() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);

        postTransfer(UUID.randomUUID().toString(), new PostTransferRequest(from.id(), to.id(), 0L))
                .expectStatus().isBadRequest();
        postTransfer(UUID.randomUUID().toString(), new PostTransferRequest(from.id(), from.id(), 5L))
                .expectStatus().isBadRequest();
        assertThat(balanceOf(from.id())).isEqualTo(100);
    }

    /**
     * 10.99 is not a rounding error on minor units, it is a client disagreeing about the unit. Truncating it
     * would move a different sum than asked and, worse, give two different bodies one fingerprint, so the
     * documented 409 on key reuse would silently become a replayed 201.
     */
    @Test
    void nonIntegerAmountIsRejectedRatherThanTruncated() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);

        client.post().uri("/transfers")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .header(CLIENT_ID, CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", from.id(), "toAccountId", to.id(), "amount", 10.99))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(balanceOf(from.id())).isEqualTo(100);
        assertThat(balanceOf(to.id())).isEqualTo(0);
    }

    @Test
    void nonIntegerInitialBalanceIsRejected() {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("initialBalance", 10.5))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Every retained key is memory the caller chooses, so its length is part of the API contract. */
    @Test
    void anOverlongIdempotencyKeyIsRejected() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);

        postTransfer("k".repeat(256), new PostTransferRequest(from.id(), to.id(), 10L))
                .expectStatus().isBadRequest();

        assertThat(balanceOf(from.id())).isEqualTo(100);
    }

    private AccountResponse openAccount(long initialBalance) {
        AccountResponse response = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("initialBalance", initialBalance))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AccountResponse.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private long balanceOf(String accountId) {
        AccountResponse response = client.get().uri("/accounts/{id}", accountId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AccountResponse.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.balance();
    }

    private RestTestClient.ResponseSpec postTransfer(String key, PostTransferRequest body) {
        return postTransfer(CLIENT, key, body);
    }

    private RestTestClient.ResponseSpec postTransfer(String clientId, String key, PostTransferRequest body) {
        return client.post().uri("/transfers")
                .header(IDEMPOTENCY_KEY, key)
                .header(CLIENT_ID, clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }
}

package com.mwilk.ledger.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key store's cap is configuration rather than a compile-time constant, and a saturated service still
 * behaves: it refuses new keys in problem+json without applying them, and keeps replaying the keys it
 * already holds — which is exactly what the retries it exists to serve depend on.
 *
 * <p>One test method, not several: the store is process-wide state shared by every method in this context,
 * so splitting the story would make the tests depend on their own ordering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledger.idempotency.max-keys=2")
@AutoConfigureRestTestClient
class LedgerCapacityTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String CLIENT_ID = "X-Client-Id";
    private static final String CLIENT = "capacity-test";

    @Autowired
    private RestTestClient client;

    @Test
    void refusesNewKeysPastTheConfiguredCapButKeepsReplayingTheOnesItHolds() {
        AccountResponse from = openAccount(100);
        AccountResponse to = openAccount(0);

        TransferResponse first = transfer("k1", from, to).expectStatus().isCreated()
                .expectBody(TransferResponse.class).returnResult().getResponseBody();
        transfer("k2", from, to).expectStatus().isCreated();
        assertThat(balanceOf(from.id())).isEqualTo(80);

        transfer("k3", from, to)
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.title").isEqualTo("Try again later");
        assertThat(balanceOf(from.id())).isEqualTo(80);

        TransferResponse replayed = transfer("k1", from, to).expectStatus().isCreated()
                .expectBody(TransferResponse.class).returnResult().getResponseBody();
        assertThat(replayed).isEqualTo(first);
        assertThat(balanceOf(from.id())).isEqualTo(80);
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

    private RestTestClient.ResponseSpec transfer(String key, AccountResponse from, AccountResponse to) {
        return client.post().uri("/transfers")
                .header(IDEMPOTENCY_KEY, key)
                .header(CLIENT_ID, CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PostTransferRequest(from.id(), to.id(), 10L))
                .exchange();
    }
}

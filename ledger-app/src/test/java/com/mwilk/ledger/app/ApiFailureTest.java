package com.mwilk.ledger.app;

import com.mwilk.ledger.app.web.OpenAccountRequest;
import com.mwilk.ledger.core.Ledger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/** A failing storage engine must still answer in problem+json and must not leak internals. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ApiFailureTest {

    @MockitoBean
    private Ledger ledger;

    @Autowired
    private RestTestClient client;

    @Test
    void unexpectedFailureIsReportedAsAProblemWithoutInternals() {
        given(ledger.openAccount(any())).willThrow(new IllegalStateException("storage unavailable"));

        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OpenAccountRequest(10L))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Internal error")
                .jsonPath("$.detail").isEqualTo("The request could not be completed.");
    }
}

package com.mwilk.ledger.app.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two guarantees the requirements lean hardest on, proved through the real stack rather than against the
 * core alone: the socket, the servlet container, the controller and the ledger are all inside the assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LedgerApiConcurrencyTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    /** A retry that arrives while the original is still in flight must not move money a second time. */
    @Test
    void concurrentRequestsWithOneKeyApplyTheTransferOnceAndAllSeeTheSameOutcome() throws Exception {
        int callers = 32;
        String from = openAccount(1_000);
        String to = openAccount(0);
        String key = UUID.randomUUID().toString();
        CyclicBarrier startTogether = new CyclicBarrier(callers);

        List<Result> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Future<Result>> attempts = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                attempts.add(pool.submit(() -> {
                    startTogether.await();
                    return transfer(key, from, to, 250);
                }));
            }
            for (Future<Result> attempt : attempts) {
                results.add(attempt.get());
            }
        }

        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(201));
        Set<String> transferIds = results.stream().map(r -> r.field("transferId")).collect(Collectors.toSet());
        assertThat(transferIds).hasSize(1);
        assertThat(balanceOf(from)).isEqualTo(750);
        assertThat(balanceOf(to)).isEqualTo(250);
    }

    /** No double-spend through the full stack: only as many transfers as the source can fund may succeed. */
    @Test
    void concurrentTransfersNeverOverdrawTheSourceAccount() throws Exception {
        int attempts = 64;
        long available = 24;
        String from = openAccount(available);
        String to = openAccount(0);
        CyclicBarrier startTogether = new CyclicBarrier(attempts);

        List<Result> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Future<Result>> posts = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String key = UUID.randomUUID().toString();
                posts.add(pool.submit(() -> {
                    startTogether.await();
                    return transfer(key, from, to, 1);
                }));
            }
            for (Future<Result> post : posts) {
                results.add(post.get());
            }
        }

        assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(available);
        assertThat(results.stream().filter(r -> r.status() == 422).count()).isEqualTo(attempts - available);
        assertThat(balanceOf(from)).isZero();
        assertThat(balanceOf(to)).isEqualTo(available);
    }

    private String openAccount(long initialBalance) throws Exception {
        Result created = send(request("/accounts")
                .POST(HttpRequest.BodyPublishers.ofString("{\"initialBalance\":" + initialBalance + "}")));
        assertThat(created.status()).isEqualTo(201);
        return created.field("id");
    }

    private long balanceOf(String accountId) throws Exception {
        Result read = send(request("/accounts/" + accountId).GET());
        assertThat(read.status()).isEqualTo(200);
        return Long.parseLong(read.field("balance"));
    }

    private Result transfer(String key, String from, String to, long amount) throws Exception {
        return send(request("/transfers")
                .header("X-Client-Id", "concurrency-test")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%d}".formatted(from, to, amount))));
    }

    private HttpRequest.Builder request(String path) {
        String port = environment.getProperty("local.server.port");
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
    }

    private Result send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }

    /** Read with a regex rather than a JSON binder: the bodies are flat and the point of the test is the wire. */
    private record Result(int status, String body) {

        String field(String name) {
            Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]+)\"?").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}

package com.mwilk.ledger.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How long a used idempotency key is remembered, and how many are kept at once.
 *
 * <p>The two together set a sustained throughput ceiling of {@code maxKeys / retention} transfers per
 * second; past it the store saturates and {@code POST /transfers} answers {@code 503} for new keys until
 * entries age out. Retries of keys the store still holds keep working, because they claim nothing.
 *
 * <p>The values here mirror the defaults the core applies when it is used without Spring, so a deployment
 * that sets neither behaves exactly like a plain {@code new InMemoryLedger()}.
 */
@ConfigurationProperties("ledger.idempotency")
record IdempotencyProperties(@DefaultValue("24h") Duration retention,
                             @DefaultValue("1000000") int maxKeys) {
}

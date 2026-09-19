package com.mwilk.ledger.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void rejectsBlankParts() {
        assertThatThrownBy(() -> new IdempotencyKey("client", " "))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Idempotency key");
        assertThatThrownBy(() -> new IdempotencyKey(null, "key"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Client id");
    }

    @Test
    void acceptsAKeyExactlyAtTheBound() {
        String atBound = "k".repeat(IdempotencyKey.MAX_LENGTH);

        assertThat(new IdempotencyKey(atBound, atBound).value()).hasSize(IdempotencyKey.MAX_LENGTH);
    }

    /** Each retained key is memory a caller chooses, so the entry cap only bounds the heap if the key is bounded too. */
    @Test
    void rejectsPartsLongerThanTheBound() {
        String tooLong = "k".repeat(IdempotencyKey.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new IdempotencyKey("client", tooLong))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Idempotency key must not exceed 255 characters");
        assertThatThrownBy(() -> new IdempotencyKey(tooLong, "key"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Client id must not exceed 255 characters");
    }
}

package com.myplus.commerce.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test — always runs on {@code mvn test}, no Docker (slice 33). */
class InvoiceNumbersTest {

    @Test
    void formats_with_prefix_and_six_digit_zero_padding() {
        assertThat(InvoiceNumbers.format(1)).isEqualTo("INV-000001");
        assertThat(InvoiceNumbers.format(123)).isEqualTo("INV-000123");
        assertThat(InvoiceNumbers.format(999999)).isEqualTo("INV-999999");
    }

    @Test
    void does_not_truncate_sequences_beyond_six_digits() {
        assertThat(InvoiceNumbers.format(1000000)).isEqualTo("INV-1000000");
    }
}

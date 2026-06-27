package com.myplus.commerce.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test — always runs on {@code mvn test}, no Docker (slice 33). */
class MoneyTest {

    @Test
    void nz_treats_null_as_zero_and_scales_to_two_dp() {
        assertThat(Money.nz(null)).isEqualByComparingTo("0.00");
        assertThat(Money.nz(new BigDecimal("5"))).isEqualByComparingTo("5.00");
    }

    @Test
    void add_and_subtract_are_null_safe() {
        assertThat(Money.add(new BigDecimal("10.00"), null)).isEqualByComparingTo("10.00");
        assertThat(Money.add(new BigDecimal("10.10"), new BigDecimal("0.20"))).isEqualByComparingTo("10.30");
        assertThat(Money.subtract(new BigDecimal("10.00"), new BigDecimal("3.50"))).isEqualByComparingTo("6.50");
        assertThat(Money.subtract(null, null)).isEqualByComparingTo("0.00");
    }

    @Test
    void multiply_rounds_half_up_to_two_dp() {
        // 2.005 * 1 -> 2.01 (HALF_UP at 2dp)
        assertThat(Money.multiply(new BigDecimal("2.005"), BigDecimal.ONE)).isEqualByComparingTo("2.01");
    }
}

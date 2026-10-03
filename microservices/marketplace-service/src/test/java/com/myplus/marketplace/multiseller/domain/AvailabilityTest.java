package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvailabilityTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("[MKT-R10.3] available = on hand − reserved − allocated − unavailable")
    void formula() {
        assertThat(Availability.available(n("10"), n("2"), n("3"), n("1"))).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("[MKT-R10.3] stock > 0 is not availability: 2 on hand, both held → cannot reserve 1")
    void stockGreaterThanZeroIsNotEnough() {
        assertThat(Availability.canReserve(n("1"), n("2"), n("2"), null, null)).isFalse();
    }

    @Test
    @DisplayName("[MKT-R10.3] oversold never reads as negative stock")
    void clampsAtZero() {
        assertThat(Availability.available(n("1"), n("3"), null, null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("[MKT-R10.2] a zero or negative request is never reservable")
    void nonPositiveRequest() {
        assertThat(Availability.canReserve(n("0"), n("5"), null, null, null)).isFalse();
        assertThat(Availability.canReserve(n("-1"), n("5"), null, null, null)).isFalse();
        assertThat(Availability.canReserve(n("5"), n("5"), null, null, null)).isTrue();
    }
}

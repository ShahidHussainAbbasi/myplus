package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AcceptanceTermsTest {

    @Test
    @DisplayName("[MKT-R10.5] source defaults: merchant 5 min to accept, 10 min hold; supplier 20/30")
    void defaults() {
        AcceptanceTerms t = AcceptanceTerms.defaults();
        assertThat(t.forSource(StockSourceType.MERCHANT))
                .isEqualTo(new AcceptanceTerms.Terms(Duration.ofMinutes(5), Duration.ofMinutes(10)));
        assertThat(t.forSource(StockSourceType.SUPPLIER).acceptWithin()).isEqualTo(Duration.ofMinutes(20));
        assertThat(t.forSource(StockSourceType.CONSIGNMENT).holdFor()).isEqualTo(Duration.ofMinutes(10));
        assertThat(t.forSource(StockSourceType.PLATFORM).acceptWithin()).isZero();
    }

    @Test
    @DisplayName("[MKT-R10.5] [MKT-R10.6] defaults are overridable per tenant, but a hold shorter than the window is refused")
    void overridesValidated() {
        AcceptanceTerms t = AcceptanceTerms.defaults()
                .set(StockSourceType.MERCHANT, new AcceptanceTerms.Terms(Duration.ofMinutes(3), Duration.ofMinutes(6)));
        assertThat(t.forSource(StockSourceType.MERCHANT).acceptWithin()).isEqualTo(Duration.ofMinutes(3));
        assertThatThrownBy(() -> new AcceptanceTerms.Terms(Duration.ofMinutes(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

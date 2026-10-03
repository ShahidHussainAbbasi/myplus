package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.PayableSnapshot;

/** FP-4c — the per-supplier figures finance stamps on business's supplier. */
class PayableBalanceServiceTest {

    @Test @DisplayName("bills still open are sent; a supplier netting below zero carries an advance")
    void figures() {
        var b = PayableBalanceService.figures("VENDOR", 7L, new BigDecimal("300"), new BigDecimal("-40"), 5L);
        assertThat(b.otherOpen()).isEqualByComparingTo("300");
        assertThat(b.advance()).isEqualByComparingTo("40");
        var none = PayableBalanceService.figures("VENDOR", 7L, null, new BigDecimal("120"), 6L);
        assertThat(none.otherOpen()).isEqualByComparingTo("0");
        assertThat(none.advance()).as("owing, not ahead").isEqualByComparingTo("0");
    }

    @Test @DisplayName("a batch of snapshots queues each supplier ONCE")
    void keyedOncePerSupplier() {
        var s1 = PayableSnapshot.builder().partyType("VENDOR").partyId(7L).build();
        var s2 = PayableSnapshot.builder().partyType("VENDOR").partyId(7L).build();
        var s3 = PayableSnapshot.builder().partyId(9L).build();
        assertThat(PayableBalanceService.keyed(List.of(s1, s2, s3))).containsOnlyKeys("VENDOR:7", "VENDOR:9");
    }
}

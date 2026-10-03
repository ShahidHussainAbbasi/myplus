package com.myplus.market.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SellerStatusTest {

    @Test
    @DisplayName("the transition table is exactly the documented one — 8 legal moves, everything else refused")
    void transitions() {
        int legal = 0;
        for (SellerStatus from : SellerStatus.values()) {
            for (SellerStatus to : SellerStatus.values()) {
                if (from.canMoveTo(to)) legal++;
            }
        }
        assertThat(legal).isEqualTo(8);
        assertThat(SellerStatus.SUSPENDED.canMoveTo(SellerStatus.WITHDRAWN)).as("leaving to dodge a suspension").isFalse();
        assertThat(SellerStatus.WITHDRAWN.canMoveTo(SellerStatus.ACTIVE)).as("back without review").isFalse();
        assertThat(SellerStatus.WITHDRAWN.canMoveTo(SellerStatus.PENDING_REVIEW)).isTrue();
        assertThat(SellerStatus.PENDING_REVIEW.canMoveTo(SellerStatus.ACTIVE)).isTrue();
        assertThat(SellerStatus.PENDING_REVIEW.canMoveTo(SellerStatus.REJECTED)).isTrue();
        assertThat(SellerStatus.REJECTED.canMoveTo(SellerStatus.PENDING_REVIEW)).isTrue();
        assertThat(SellerStatus.ACTIVE.canMoveTo(SellerStatus.SUSPENDED)).isTrue();
        assertThat(SellerStatus.SUSPENDED.canMoveTo(SellerStatus.ACTIVE)).isTrue();
        // the dangerous shortcuts
        assertThat(SellerStatus.REJECTED.canMoveTo(SellerStatus.ACTIVE)).as("rejected straight to active").isFalse();
        assertThat(SellerStatus.SUSPENDED.canMoveTo(SellerStatus.PENDING_REVIEW)).as("suspension dodged by re-applying").isFalse();
        assertThat(SellerStatus.ACTIVE.canMoveTo(null)).isFalse();
    }

    @Test
    @DisplayName("exactly four policy types bind a seller")
    void seller_policies() {
        assertThat(java.util.Arrays.stream(PolicyType.values()).filter(PolicyType::sellerMustAccept))
                .containsExactly(PolicyType.SELLER_AGREEMENT, PolicyType.DATA_SHARING, PolicyType.COMMISSION,
                        PolicyType.RETURNS_REFUNDS);
        assertThat(PolicyType.parse(" commission ")).isEqualTo(PolicyType.COMMISSION);
        assertThat(PolicyType.parse("NOPE")).isNull();
    }
}

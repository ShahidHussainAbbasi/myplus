package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MKT-2e — the scorecard's counting rules (slice doc mkt-2e-merchant-performance.md §1). */
class SellerPerformanceTest {

    static final LocalDateTime T = LocalDateTime.of(2026, 10, 1, 10, 0);
    static final LocalDateTime NOW = T.plusDays(5);

    static SellerPerformance.Part accepted(int minutes, Integer promise, Integer deliveredAfterHours) {
        LocalDateTime decided = T.plusMinutes(minutes);
        return new SellerPerformance.Part(7L, "ACCEPTED", T, decided,
                deliveredAfterHours == null ? null : decided.plusHours(deliveredAfterHours), promise, null, null);
    }

    static SellerPerformance.Part missed(String status, String party, String shortageStatus) {
        return new SellerPerformance.Part(7L, status, T, null, null, 24, party, shortageStatus);
    }

    static List<SellerPerformance.Part> times(int n, SellerPerformance.Part p) {
        List<SellerPerformance.Part> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(p);
        return out;
    }

    @Test
    @DisplayName("[MKT-R20.3] accepted ÷ (accepted + missed); a miss is the seller's when the shortage names it or there is none")
    void acceptanceRate() {
        List<SellerPerformance.Part> parts = new ArrayList<>(times(6, accepted(10, null, null)));
        parts.add(missed("REJECTED", "MERCHANT", "RECORDED"));
        parts.add(missed("EXPIRED", "MERCHANT", "UPHELD"));
        parts.add(missed("REJECTED", null, null));                       // before MKT-2b: no record → the seller's
        SellerPerformance.Score s = SellerPerformance.score(parts, NOW);
        assertThat(s.accepted()).isEqualTo(6);
        assertThat(s.missed()).isEqualTo(3);
        assertThat(s.acceptanceRate()).isEqualTo(6d / 9);
        assertThat(s.avgMinutesToAccept()).isEqualTo(10L);
        assertThat(s.flags(false)).containsExactly(SellerPerformance.FLAG_ACCEPTANCE);
    }

    @Test
    @DisplayName("[MKT-R12.4] [MKT-R11.4] someone else's cause, an overturned one and a disputed one do not count against the seller")
    void excusedAndDisputed() {
        List<SellerPerformance.Part> parts = new ArrayList<>(times(5, accepted(4, null, null)));
        parts.add(missed("REJECTED", "SUPPLIER", "RECORDED"));
        parts.add(missed("REJECTED", "PLATFORM", "RECORDED"));
        parts.add(missed("EXPIRED", "MERCHANT", "OVERTURNED"));
        parts.add(missed("REJECTED", "MERCHANT", "DISPUTED"));
        SellerPerformance.Score s = SellerPerformance.score(parts, NOW);
        assertThat(s.missed()).isZero();
        assertThat(s.excused()).isEqualTo(3);
        assertThat(s.disputed()).isEqualTo(1);
        assertThat(s.acceptanceRate()).isEqualTo(1d);
        assertThat(s.flags(false)).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R20.3] not decided yet, and cancelled before the seller answered, are not counted")
    void undecidedNotCounted() {
        List<SellerPerformance.Part> parts = new ArrayList<>(times(5, accepted(1, null, null)));
        parts.add(new SellerPerformance.Part(7L, "OFFERED", T, null, null, 24, null, null));
        parts.add(new SellerPerformance.Part(7L, "UNASSIGNED", T, null, null, 24, null, null));
        parts.add(new SellerPerformance.Part(7L, "CANCELLED", T, null, null, 24, null, null));
        SellerPerformance.Score s = SellerPerformance.score(parts, NOW);
        assertThat(s.accepted() + s.missed() + s.excused() + s.disputed()).isEqualTo(5);
        assertThat(s.acceptanceRate()).isEqualTo(1d);
    }

    @Test
    @DisplayName("[MKT-R20.3] fewer than 5 orders: no rate, no flag ('not enough orders yet')")
    void tooFewOrders() {
        List<SellerPerformance.Part> parts = List.of(accepted(1, 4, 9), missed("REJECTED", "MERCHANT", "RECORDED"),
                missed("REJECTED", "MERCHANT", "RECORDED"), missed("EXPIRED", "MERCHANT", "RECORDED"));
        SellerPerformance.Score s = SellerPerformance.score(parts, NOW);
        assertThat(s.acceptanceRate()).isNull();
        assertThat(s.onTimeRate()).isNull();
        assertThat(s.flags(false)).isEmpty();
        assertThat(s.flags(true)).as("late for cash orders needs no volume").containsExactly(SellerPerformance.FLAG_COD);
    }

    @Test
    @DisplayName("[MKT-R20.3] on time = delivered by acceptance + the longest promise; past the promise and not delivered is late")
    void onTime() {
        List<SellerPerformance.Part> parts = new ArrayList<>();
        parts.addAll(times(8, accepted(5, 24, 20)));                     // delivered in 20 h of a 24 h promise
        parts.add(accepted(5, 24, 24));                                  // exactly on the promise: on time
        parts.add(accepted(5, 4, 6));                                    // 6 h on a 4 h promise: late
        parts.add(accepted(5, 24, null));                                // promise long passed (NOW = +5 days), not delivered: late
        parts.add(new SellerPerformance.Part(7L, "ACCEPTED", NOW.minusHours(1), NOW.minusHours(1), null, 24, null, null)); // not due yet
        parts.add(accepted(5, null, 3));                                 // no promise: not measured
        SellerPerformance.Score s = SellerPerformance.score(parts, NOW);
        assertThat(s.due()).isEqualTo(11);
        assertThat(s.onTime()).isEqualTo(9);
        assertThat(s.onTimeRate()).isEqualTo(9d / 11);
        assertThat(s.flags(false)).containsExactly(SellerPerformance.FLAG_ON_TIME);
    }

    @Test
    @DisplayName("[MKT-R20.3] thresholds: 80% accepted and 90% on time are not flagged; just under is")
    void thresholds() {
        List<SellerPerformance.Part> parts = new ArrayList<>(times(8, accepted(1, 24, 1)));
        parts.addAll(times(2, missed("REJECTED", "MERCHANT", "RECORDED")));
        assertThat(SellerPerformance.score(parts, NOW).flags(false)).as("8 of 10 = 80%").isEmpty();
        parts.add(missed("REJECTED", "MERCHANT", "RECORDED"));
        assertThat(SellerPerformance.score(parts, NOW).flags(false)).as("8 of 11").containsExactly(SellerPerformance.FLAG_ACCEPTANCE);
    }
}

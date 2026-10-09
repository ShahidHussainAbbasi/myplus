package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OfferEligibilityTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private static OfferCandidate base(Approval a, boolean active, StockSourceType src, Regulated reg,
            Set<String> areas, String qty, Instant synced) {
        return new OfferCandidate(1, 9, 101, src, a, active, reg, areas, new BigDecimal("1000"), null,
                new BigDecimal(qty), 4, new BigDecimal("4.5"), 12, 7, 2.0, 0.9, synced);
    }

    private static OfferCandidate ok() {
        return base(Approval.APPROVED, true, StockSourceType.MERCHANT, Regulated.NONE, Set.of("Karachi"), "3",
                NOW.minusSeconds(30));
    }

    private static EligibilityContext ctx(String qty) {
        return EligibilityContext.phase1("karachi", new BigDecimal(qty), NOW);
    }

    @Test
    @DisplayName("[MKT-R7.6] an approved, active, in-area, fresh, stocked merchant offer is eligible")
    void eligible() {
        assertThat(OfferEligibility.refusal(ok(), ctx("1"))).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R7.6] [MKT-R22.2] unapproved offers are refused with a sentence, not a code")
    void unapproved() {
        for (Approval a : new Approval[] {Approval.DRAFT, Approval.PENDING_REVIEW, Approval.REJECTED, Approval.SUSPENDED}) {
            assertThat(OfferEligibility.refusal(base(a, true, StockSourceType.MERCHANT, Regulated.NONE,
                    Set.of("Karachi"), "3", NOW), ctx("1"))).as(a.name())
                    .contains("This offer is not available. Please choose another offer.");
        }
    }

    @Test
    @DisplayName("[MKT-R7.6] an inactive (suspended) seller's offers are not eligible")
    void inactiveSeller() {
        assertThat(OfferEligibility.isEligible(base(Approval.APPROVED, false, StockSourceType.MERCHANT,
                Regulated.NONE, Set.of("Karachi"), "3", NOW), ctx("1"))).isFalse();
    }

    @Test
    @DisplayName("[MKT-R20.2] [MKT-R4.4] Phase 1 refuses supplier, platform and consignment stock")
    void phaseSources() {
        for (StockSourceType s : new StockSourceType[] {StockSourceType.PLATFORM, StockSourceType.SUPPLIER,
                StockSourceType.CONSIGNMENT}) {
            assertThat(OfferEligibility.isEligible(base(Approval.APPROVED, true, s, Regulated.NONE,
                    Set.of("Karachi"), "3", NOW), ctx("1"))).as(s.name()).isFalse();
        }
    }

    @Test
    @DisplayName("[MKT-R20.2] [MKT-R7.6] prescription and restricted products are refused while the safety flag is on")
    void regulated() {
        OfferCandidate rx = base(Approval.APPROVED, true, StockSourceType.MERCHANT, Regulated.PRESCRIPTION,
                Set.of("Karachi"), "3", NOW);
        assertThat(OfferEligibility.refusal(rx, ctx("1"))).contains("This product cannot be ordered on the marketplace.");
        EligibilityContext off = new EligibilityContext("Karachi", BigDecimal.ONE, null, null, 6, false,
                Duration.ofMinutes(30), NOW);
        assertThat(OfferEligibility.isEligible(rx, off)).as("Phase 6 with the flag explicitly off").isTrue();
    }

    @Test
    @DisplayName("[MKT-R7.6] [MKT-R20.1] an offer outside the customer's city is refused, naming the city")
    void deliveryArea() {
        assertThat(OfferEligibility.refusal(ok(), EligibilityContext.phase1("Lahore", BigDecimal.ONE, NOW)))
                .contains("This seller does not deliver to Lahore.");
        assertThat(OfferEligibility.isEligible(ok(), EligibilityContext.phase1(null, BigDecimal.ONE, NOW)))
                .as("browsing with no city chosen yet shows the offer").isTrue();
    }

    @Test
    @DisplayName("[MKT-R10.3] [MKT-R7.6] not enough stock for the quantity is refused")
    void stock() {
        assertThat(OfferEligibility.isEligible(ok(), ctx("3"))).isTrue();
        assertThat(OfferEligibility.isEligible(ok(), ctx("4"))).isFalse();
    }

    @Test
    @DisplayName("[MKT-R18.5] a stale projection is never shown as available: 'We are checking availability'")
    void stale() {
        OfferCandidate stale = base(Approval.APPROVED, true, StockSourceType.MERCHANT, Regulated.NONE,
                Set.of("Karachi"), "3", NOW.minus(Duration.ofMinutes(31)));
        assertThat(OfferEligibility.refusal(stale, ctx("1")).orElseThrow()).startsWith("We are checking availability");
    }

    @Test
    @DisplayName("[MKT-R7.4] [MKT-R7.6] the operator's price floor and ceiling bind the seller's price")
    void priceLimits() {
        EligibilityContext limits = new EligibilityContext("Karachi", BigDecimal.ONE, new BigDecimal("1100"),
                new BigDecimal("2000"), 1, true, Duration.ofMinutes(30), NOW);
        assertThat(OfferEligibility.refusal(ok(), limits)).contains("This offer's price is outside the allowed range.");
        EligibilityContext ceiling = new EligibilityContext("Karachi", BigDecimal.ONE, null, new BigDecimal("999"), 1,
                true, Duration.ofMinutes(30), NOW);
        assertThat(OfferEligibility.isEligible(ok(), ceiling)).isFalse();
    }

    @Test
    @DisplayName("[MKT-R4.2] [MKT-R7.6] a PLATFORM offer is shown only while a warehouse is named; the other guardrails still apply")
    void platformStock() {
        OfferCandidate own = base(Approval.APPROVED, true, StockSourceType.PLATFORM, Regulated.NONE, Set.of("Karachi"), "3",
                NOW.minusSeconds(30));
        EligibilityContext p1 = ctx("1");
        EligibilityContext on = new EligibilityContext(p1.city(), p1.quantity(), null, null, p1.enabledPhase(),
                p1.blockRegulated(), p1.staleAfter(), p1.now(), true);
        assertThat(OfferEligibility.refusal(own, p1)).contains("This offer is not available yet. Please choose another offer.");
        assertThat(OfferEligibility.refusal(own, on)).isEmpty();
        assertThat(OfferEligibility.refusal(base(Approval.APPROVED, true, StockSourceType.PLATFORM, Regulated.NONE,
                Set.of("Lahore"), "3", NOW.minusSeconds(30)), on)).contains("This seller does not deliver to karachi.");
    }
}

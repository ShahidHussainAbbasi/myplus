package com.myplus.business_service.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #28 — a quote past its validity is EXPIRED, wherever it is read.
 *
 * <p>Design: {@code microservices/docs/slices/quote-document.md}. Pure logic, no database, no Docker — it runs
 * on every {@code mvn test}, because this one method decides what a customer-facing document says about an
 * offer that has lapsed.
 *
 * <p><b>The defect being prevented:</b> EXPIRED is DERIVED, never stored. A quote past {@code validUntil}
 * still holds {@code status = SENT} in its row, so any reader that publishes the raw field prints SENT onto a
 * sheet the server refuses every action on — a priced offer the customer would reasonably expect to be
 * honoured, and a shop that cannot explain why the system will not convert it.
 *
 * <p><b>Why this is a unit test and not a Cypress case.</b> It cannot be driven through the API. Seeding an
 * expired quote would need {@code validUntil} in the past, and both routes there are closed by design:
 * {@code create()} always server-sets it to {@code now + validityDays}, and {@code validityDays()} clamps a
 * non-positive setting back to the 30-day default (a zero-day validity would expire every quote instantly).
 * Both guards are correct and neither should be weakened to make a test reachable — so the derivation is
 * asserted here, where it actually lives, and the Cypress gate asserts the two halves it CAN reach: that the
 * document endpoint publishes {@code effectiveStatus}, and that the renderer prints whatever it is given.
 */
class QuoteEffectiveStatusTest {

    private SalesQuote quote(QuoteStatus status, LocalDate validUntil) {
        SalesQuote q = new SalesQuote();
        q.setStatus(status);
        q.setValidUntil(validUntil);
        return q;
    }

    @Test
    @DisplayName("a SENT quote whose validity has passed reads EXPIRED")
    void sentQuotePastValidityIsExpired() {
        // THE DEFECT. The stored field still says SENT; every reader must be told EXPIRED.
        SalesQuote q = quote(QuoteStatus.SENT, LocalDate.now().minusDays(1));

        assertThat(q.getEffectiveStatus()).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(q.getStatus())
                .as("the stored status is deliberately untouched — no job rewrites a customer-facing document")
                .isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("a quote valid until today is still live")
    void validUntilTodayIsStillOpen() {
        /*
         * The boundary, and the one worth pinning: `isBefore(now)` is exclusive, so the last day of validity
         * is a day the customer can still accept on. An off-by-one here would expire every quote a day early
         * — silently, and only ever noticed by the customer being told no.
         */
        SalesQuote q = quote(QuoteStatus.SENT, LocalDate.now());

        assertThat(q.getEffectiveStatus()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("expiry never overrides a CLOSED state")
    void closedStatesAreNotReopenedAsExpired() {
        /*
         * A converted quote is a historical record of a sale that happened; a rejected one records what was
         * offered and declined. Neither becomes "expired" by the calendar moving on — and CONVERTED in
         * particular must never read as EXPIRED, because the invoice it produced is real and still owed.
         */
        LocalDate lapsed = LocalDate.now().minusDays(90);

        assertThat(quote(QuoteStatus.CONVERTED, lapsed).getEffectiveStatus())
                .isEqualTo(QuoteStatus.CONVERTED);
        assertThat(quote(QuoteStatus.REJECTED, lapsed).getEffectiveStatus())
                .isEqualTo(QuoteStatus.REJECTED);
    }

    @Test
    @DisplayName("a quote with no expiry date never expires")
    void nullValidUntilNeverExpires() {
        // Legacy rows predate the validity field. They must keep working rather than reading as expired,
        // which would retroactively withdraw offers nobody withdrew.
        assertThat(quote(QuoteStatus.SENT, null).getEffectiveStatus()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("a DRAFT past its validity still reads EXPIRED, not DRAFT")
    void draftPastValidityIsExpired() {
        // DRAFT is an open state, so the same rule applies: an abandoned draft from last quarter is not
        // something to hand a customer as a current working document.
        assertThat(quote(QuoteStatus.DRAFT, LocalDate.now().minusDays(5)).getEffectiveStatus())
                .isEqualTo(QuoteStatus.EXPIRED);
    }
}

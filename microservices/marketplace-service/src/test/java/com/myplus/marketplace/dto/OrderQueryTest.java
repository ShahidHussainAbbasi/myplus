package com.myplus.marketplace.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OMS O4 — the back-office list filter.
 *
 * <p>Pure logic: no Spring, no database, no Docker, so it runs on every {@code mvn test} — which matters on this
 * project because the Testcontainers suites skip on the dev machine.
 *
 * <p>These are the cases where getting it wrong is SILENT. An uncapped page size is the unbounded read (OMS-7)
 * coming back through the front door; a blank filter matched literally returns nothing and looks like an empty
 * shop; an exclusive end date drops the last day of every range an operator asks for.
 */
class OrderQueryTest {

    // ── the cap is the whole point of paginating ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a caller cannot ask for more than the cap — this is what closes OMS-7")
    void sizeIsCapped() {
        assertThat(OrderQuery.of(0, 100_000, null, null, null, null, null, null).getSize())
                .as("?size=100000 would be the unbounded read again, with extra steps")
                .isEqualTo(OrderQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("a sensible size is honoured")
    void sizeIsHonouredBelowTheCap() {
        assertThat(OrderQuery.of(0, 10, null, null, null, null, null, null).getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("a missing or nonsensical size falls back to the default rather than to everything")
    void sizeDefaults() {
        assertThat(OrderQuery.of(0, null, null, null, null, null, null, null).getSize())
                .isEqualTo(OrderQuery.DEFAULT_SIZE);
        assertThat(OrderQuery.of(0, 0, null, null, null, null, null, null).getSize())
                .as("size=0 must not mean 'no limit'")
                .isEqualTo(OrderQuery.DEFAULT_SIZE);
        assertThat(OrderQuery.of(0, -5, null, null, null, null, null, null).getSize())
                .isEqualTo(OrderQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("a negative page is the first page, not an error")
    void pageClamps() {
        assertThat(OrderQuery.of(-3, null, null, null, null, null, null, null).getPage()).isZero();
        assertThat(OrderQuery.of(null, null, null, null, null, null, null, null).getPage()).isZero();
        assertThat(OrderQuery.of(4, null, null, null, null, null, null, null).getPage()).isEqualTo(4);
    }

    // ── blank means absent ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a blank filter is dropped, not matched literally")
    void blankFiltersAreDropped() {
        OrderQuery q = OrderQuery.of(0, 25, "  ", "", "   ", null, null, "  ");
        // Matching "" literally would return nothing, and the operator would think the shop had no orders.
        assertThat(q.getStatus()).isNull();
        assertThat(q.getPaymentStatus()).isNull();
        assertThat(q.getSource()).isNull();
        assertThat(q.getQ()).isNull();
        assertThat(q.hasText()).isFalse();
        assertThat(q.likePattern()).isNull();
    }

    @Test
    @DisplayName("filters are upper-cased so the caller's casing does not decide whether rows match")
    void filtersAreNormalised() {
        OrderQuery q = OrderQuery.of(0, 25, "new", "paid", "storefront", null, null, null);
        assertThat(q.getStatus()).isEqualTo("NEW");
        assertThat(q.getPaymentStatus()).isEqualTo("PAID");
        assertThat(q.getSource()).isEqualTo("STOREFRONT");
    }

    // ── free-text search ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("text search is a lower-cased contains pattern, trimmed")
    void textBecomesALowerCasedLikePattern() {
        OrderQuery q = OrderQuery.of(0, 25, null, null, null, null, null, "  SO-000123 ");
        assertThat(q.getQ()).isEqualTo("SO-000123");
        assertThat(q.likePattern())
                .as("both sides are lower-cased in the query, so a merchant typing 'so-' still finds SO-")
                .isEqualTo("%so-000123%");
        assertThat(q.hasText()).isTrue();
    }

    // ── dates ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the range is inclusive at BOTH ends — the last day is not silently dropped")
    void dateRangeIsInclusive() {
        OrderQuery q = OrderQuery.of(0, 25, null, null, null,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null);

        assertThat(q.getFrom()).isEqualTo(LocalDate.of(2026, 3, 1).atStartOfDay());
        // created_at is a TIMESTAMP. Comparing it against midnight on the 31st would exclude everything that
        // happened during the 31st — "1st to 31st" returning nothing from the 31st is a wrong answer an
        // operator has no reason to doubt.
        assertThat(q.getTo()).isEqualTo(LocalDate.of(2026, 3, 31).atTime(java.time.LocalTime.MAX));
        assertThat(q.getTo().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("an open-ended range is allowed at either end")
    void openEndedRanges() {
        assertThat(OrderQuery.of(0, 25, null, null, null, LocalDate.of(2026, 3, 1), null, null).getTo()).isNull();
        assertThat(OrderQuery.of(0, 25, null, null, null, null, LocalDate.of(2026, 3, 31), null).getFrom()).isNull();
    }

    @Test
    @DisplayName("the default page asks for one page, not the whole shop")
    void firstPageIsBounded() {
        OrderQuery q = OrderQuery.firstPage();
        assertThat(q.getPage()).isZero();
        assertThat(q.getSize()).isEqualTo(OrderQuery.DEFAULT_SIZE);
        assertThat(q.getStatus()).isNull();
    }
}

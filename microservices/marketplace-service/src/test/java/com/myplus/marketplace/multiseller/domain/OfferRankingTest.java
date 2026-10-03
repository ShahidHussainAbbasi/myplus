package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Source §7 and §18, using the source's own two offers for the Samsung Galaxy A32 128GB Black. */
class OfferRankingTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final EligibilityContext KARACHI = EligibilityContext.phase1("Karachi", BigDecimal.ONE, NOW);

    /** Shahzad Mobile Shop: Rs 52,000, today, 2.1 km, 12 months, 4.7 */
    private static OfferCandidate shahzad() {
        return offer(1, 101, StockSourceType.MERCHANT, "52000", 4, "4.7", 12, 7, 2.1);
    }

    /** Mobile Distributor: Rs 51,500, tomorrow, 18 km, 6 months, 4.1 */
    private static OfferCandidate distributor() {
        return offer(2, 102, StockSourceType.MERCHANT, "51500", 24, "4.1", 6, 7, 18.0);
    }

    static OfferCandidate offer(long id, long seller, StockSourceType src, String price, int promiseHours,
            String rating, int warranty, int returns, Double km) {
        return new OfferCandidate(id, 9, seller, src, Approval.APPROVED, true, Regulated.NONE, Set.of("Karachi"),
                new BigDecimal(price), null, new BigDecimal("5"), promiseHours, new BigDecimal(rating), warranty,
                returns, km, 0.95, NOW.minusSeconds(60));
    }

    private static List<Long> ids(List<OfferCandidate> ranked) {
        return ranked.stream().map(OfferCandidate::offerId).toList();
    }

    @Test
    @DisplayName("[MKT-R7.1] [MKT-R18.4] LOWEST_PRICE puts the distributor first")
    void lowestPrice() {
        assertThat(ids(OfferRanker.rank(List.of(shahzad(), distributor()), OfferSort.LOWEST_PRICE, KARACHI)))
                .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("[MKT-R7.3] the cheapest is never forced: FASTEST, NEAREST, QUALITY and WARRANTY all put Shahzad first")
    void cheapestNotForced() {
        for (OfferSort s : List.of(OfferSort.FASTEST, OfferSort.NEAREST, OfferSort.QUALITY, OfferSort.WARRANTY)) {
            assertThat(ids(OfferRanker.rank(List.of(distributor(), shahzad()), s, KARACHI))).as(s.name())
                    .containsExactly(1L, 2L);
        }
    }

    @Test
    @DisplayName("[MKT-R18.4] with no customer sort, the operator default chain decides (promise first)")
    void defaultChain() {
        assertThat(ids(OfferRanker.rank(List.of(distributor(), shahzad()), null, KARACHI))).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("[MKT-R7.1] an unknown distance never wins NEAREST")
    void unknownDistanceLast() {
        OfferCandidate noLocation = offer(3, 103, StockSourceType.MERCHANT, "50000", 4, "4.9", 12, 7, null);
        assertThat(ids(OfferRanker.rank(List.of(noLocation, distributor()), OfferSort.NEAREST, KARACHI)))
                .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("[MKT-R7.1] PROMOTION ranks the biggest seller-funded discount first; effective price honours it")
    void promotion() {
        OfferCandidate promo = new OfferCandidate(4, 9, 104, StockSourceType.MERCHANT, Approval.APPROVED, true,
                Regulated.NONE, Set.of("Karachi"), new BigDecimal("53000"), new BigDecimal("2000"), BigDecimal.TEN,
                48, new BigDecimal("4.0"), 6, 7, 5.0, 0.9, NOW);
        assertThat(promo.effectivePrice()).isEqualByComparingTo("51000");
        assertThat(ids(OfferRanker.rank(List.of(shahzad(), promo), OfferSort.PROMOTION, KARACHI)))
                .containsExactly(4L, 1L);
        assertThat(ids(OfferRanker.rank(List.of(shahzad(), promo, distributor()), OfferSort.LOWEST_PRICE, KARACHI)))
                .containsExactly(4L, 2L, 1L);
    }

    @Test
    @DisplayName("[MKT-R7.6] a sort never bypasses guardrails: an unapproved cheaper offer is not ranked at all")
    void filtersBeforeSort() {
        OfferCandidate cheapPending = new OfferCandidate(5, 9, 105, StockSourceType.MERCHANT,
                Approval.PENDING_REVIEW, true, Regulated.NONE, Set.of("Karachi"), new BigDecimal("1000"), null,
                BigDecimal.TEN, 1, new BigDecimal("5"), 24, 30, 0.1, 1.0, NOW);
        assertThat(ids(OfferRanker.rank(List.of(cheapPending, shahzad()), OfferSort.LOWEST_PRICE, KARACHI)))
                .containsExactly(1L);
    }

    @Test
    @DisplayName("[MKT-R18.4] equal offers come back in the same order every time")
    void deterministic() {
        OfferCandidate a = offer(7, 107, StockSourceType.MERCHANT, "100", 4, "4", 6, 7, 1.0);
        OfferCandidate b = offer(6, 106, StockSourceType.MERCHANT, "100", 4, "4", 6, 7, 1.0);
        assertThat(ids(OfferRanker.rank(List.of(a, b), OfferSort.LOWEST_PRICE, KARACHI))).containsExactly(6L, 7L);
        assertThat(ids(OfferRanker.rank(List.of(b, a), OfferSort.LOWEST_PRICE, KARACHI))).containsExactly(6L, 7L);
    }

    @Test
    @DisplayName("[MKT-R7.1] an unknown sort from the URL falls back to the default instead of failing the page")
    void parseSort() {
        assertThat(OfferSort.parse("lowest_price")).isEqualTo(OfferSort.LOWEST_PRICE);
        assertThat(OfferSort.parse("<script>")).isNull();
        assertThat(OfferSort.parse(null)).isNull();
    }
}

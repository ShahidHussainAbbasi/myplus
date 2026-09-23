package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.commerce.contracts.dto.StockPick;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * COGS-2 — a sale whose batches are PARTLY costed must still book the whole cost.
 *
 * <p>⚠ MONEY. {@code cogsFromPicks} summed only the picks that had a unit cost and fell back to the line
 * snapshot only when NONE did. A sale mixing the two posted a PARTIAL cost and said nothing: the "did any
 * pick have a cost?" flag was true, so no fallback ran and no warning was logged.
 *
 * <p><b>The worked example these cases are built from — INV-000011, org 13, 2026-09-23.</b> Two lines
 * costing 250 and 350. FEFO drew the first from a batch carrying no purchase price, so its pick had no unit
 * cost and was skipped, while the second line's 350 kept the flag true. COGS posted <b>350</b> against a
 * true cost of <b>600</b>, and the invoice reported a profit of 330 where 80 was right — overstated by more
 * than three times. The trial balance still balanced to the penny, because the entry was internally
 * consistent; it was simply missing a quarter of the cost. That is what makes this class of defect
 * expensive: nothing downstream looks wrong.
 *
 * <p>Pure JUnit — no Spring, no database. {@code SellBatchRepo} is only touched by {@code cogs()}.
 */
class SaleCostingMixedBatchTest {

    private static final long PRODUCT_A = 8849L;   // costs 250, drawn from a batch with NO purchase price
    private static final long PRODUCT_B = 8850L;   // costs 350, drawn from a properly costed batch

    private final SaleCosting costing = new SaleCosting(mock(com.myplus.business_service.repository.SellBatchRepo.class));

    private static StockPick pick(long productId, String qty, String unitCost) {
        StockPick p = new StockPick();
        p.setItemId(productId);
        p.setQuantity(new BigDecimal(qty));
        p.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
        return p;
    }

    /** A sale line carrying SF-10's latest-purchase-rate snapshot — the fallback cost. */
    private static SagaLine line(long productId, float qty, String costPrice) {
        return new SagaLine(productId, qty, new BigDecimal("300"), BigDecimal.ZERO,
                new BigDecimal("300"), new BigDecimal("300"), null,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300"), new BigDecimal("300"), null,
                costPrice == null ? null : new BigDecimal(costPrice), null, null,
                null, null, null, null);
    }

    @Test
    @DisplayName("⭐⭐ THE DEFECT: a costless batch beside a costed one no longer books only half the cost")
    void aMixedSaleBooksTheWholeCost() {
        List<StockPick> picks = List.of(
                pick(PRODUCT_A, "1", null),      // the batch with no purchase price
                pick(PRODUCT_B, "1", "350"));
        List<SagaLine> lines = List.of(line(PRODUCT_A, 1f, "250"), line(PRODUCT_B, 1f, "350"));

        BigDecimal cogs = costing.cogsFromPicks(picks, lines);

        assertThat(cogs).as("250 from the line snapshot + 350 from the batch — NOT the 350 this posted")
                .isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("⭐ and the profit that follows is the one the owner expects")
    void theProfitReconciles() {
        // INV-000011 exactly: goods 700, a document-level trade discount of 20, cost 600.
        BigDecimal revenue = new BigDecimal("700.00");
        BigDecimal tradeDiscount = new BigDecimal("20.00");
        BigDecimal cogs = costing.cogsFromPicks(
                List.of(pick(PRODUCT_A, "1", null), pick(PRODUCT_B, "1", "350")),
                List.of(line(PRODUCT_A, 1f, "250"), line(PRODUCT_B, 1f, "350")));

        assertThat(revenue.subtract(tradeDiscount).subtract(cogs))
                .as("700 − 20 discount − 600 cost = 80; the defect reported 330")
                .isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("a fully costed sale is unchanged — the common case must not move")
    void afullyCostedSaleIsUnchanged() {
        BigDecimal cogs = costing.cogsFromPicks(
                List.of(pick(PRODUCT_A, "2", "250"), pick(PRODUCT_B, "1", "350")),
                List.of(line(PRODUCT_A, 2f, "999"), line(PRODUCT_B, 1f, "999")));

        assertThat(cogs).as("the BATCH cost wins wherever it exists — never the snapshot")
                .isEqualByComparingTo("850.00");
    }

    @Test
    @DisplayName("⭐ the batch cost is preferred even when it DISAGREES with the line snapshot")
    void theBatchCostOutranksTheSnapshot() {
        // Cost follows the goods: the batch that actually left, not the latest purchase rate. A snapshot of
        // 999 must not displace a real batch cost of 250, or a price rise would restate old sales.
        BigDecimal cogs = costing.cogsFromPicks(
                List.of(pick(PRODUCT_A, "1", "250")), List.of(line(PRODUCT_A, 1f, "999")));

        assertThat(cogs).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("every pick costless: falls back for all of them, as it always did")
    void aFullyUncostedSaleStillFallsBack() {
        BigDecimal cogs = costing.cogsFromPicks(
                List.of(pick(PRODUCT_A, "1", null), pick(PRODUCT_B, "2", null)),
                List.of(line(PRODUCT_A, 1f, "250"), line(PRODUCT_B, 2f, "350")));

        assertThat(cogs).as("250 + 2×350").isEqualByComparingTo("950.00");
    }

    @Test
    @DisplayName("⭐ a pick with no cost ANYWHERE contributes zero — wrong, but never silently")
    void anUnpriceableLineContributesZeroLoudly() {
        /*
         * Neither the batch nor the product's purchase history knows what this cost. Zero is the only honest
         * answer available, and the service logs it at ERROR naming the product. The case pins the arithmetic
         * so a future change cannot quietly start inventing a cost instead.
         */
        BigDecimal cogs = costing.cogsFromPicks(
                List.of(pick(PRODUCT_A, "1", null), pick(PRODUCT_B, "1", "350")),
                List.of(line(PRODUCT_A, 1f, null), line(PRODUCT_B, 1f, "350")));

        assertThat(cogs).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("no picks at all: the pre-P3 path, unchanged")
    void noPicksUsesTheSnapshot() {
        assertThat(costing.cogsFromPicks(List.of(), List.of(line(PRODUCT_A, 2f, "250"))))
                .isEqualByComparingTo("500.00");
        assertThat(costing.cogsFromPicks(null, List.of(line(PRODUCT_A, 2f, "250"))))
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a partial-quantity pick costs only what it took")
    void aPartialPickCostsWhatItTook() {
        // FEFO splits a line across two batches: 3 units from a costed one, 2 from a costless one.
        BigDecimal cogs = costing.cogsFromPicks(
                List.of(pick(PRODUCT_A, "3", "100"), pick(PRODUCT_A, "2", null)),
                List.of(line(PRODUCT_A, 5f, "250")));

        assertThat(cogs).as("3×100 batch + 2×250 snapshot").isEqualByComparingTo("800.00");
    }
}

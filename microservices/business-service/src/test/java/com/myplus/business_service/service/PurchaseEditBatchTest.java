package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.myplus.business_service.entity.Purchase;
import com.myplus.commerce.contracts.dto.StockPurchaseAdjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-3 — what an EDITED bill tells inventory about its batch. Before: only a quantity change (or a Per-batch re-price)
 * reached the batch, so a corrected expiry, batch number or cost stayed old on it (owner.pharma@, bill 3106: expiry
 * 2027-09-09 on the bill, 2027-10-09 on its batch). Design: selling-price-per-purchase-analysis.md §12.6.
 */
class PurchaseEditBatchTest {

    private static final LocalDate EXP = LocalDate.of(2027, 10, 9);
    private static final BigDecimal RATE = new BigDecimal("260.00");

    /** The bill AFTER the edit: 10 at 260, batch T25791, expiring EXP, booked into stock entry 9963. */
    private static Purchase saved(Long stockEntryId) {
        Purchase p = new Purchase();
        p.setProductId(11064L);
        p.setQuantity(10f);
        p.setBatchNo("T25791");
        p.setBexpDate(EXP);
        p.setBpurchaseRate(RATE);
        p.setStockEntryId(stockEntryId);
        PurchaseService.stampPaidTotal(p);
        return p;
    }

    @Test
    @DisplayName("nothing about the batch changed, quantity kept: nothing is sent (no remote call for a vendor-only edit)")
    void unchanged_sends_nothing() {
        assertThat(PurchaseService.batchAdjustForEdit(saved(9963L), 0f, "T25791", EXP, RATE, null)).isNull();
    }

    @Test
    @DisplayName("bill 3106: ONLY the expiry corrected — the batch is told, by its id, with the new expiry")
    void expiry_only_reaches_the_batch() {
        StockPurchaseAdjust a = PurchaseService.batchAdjustForEdit(saved(9963L), 0f, "T25791", LocalDate.of(2027, 9, 9), RATE, null);
        assertThat(a).isNotNull();
        assertThat(a.getStockEntryId()).isEqualTo(9963L);
        assertThat(a.getExpiryDate()).isEqualTo(EXP);
        assertThat(a.getDelta()).isZero();
        assertThat(a.getNewBatchNo()).as("not renamed").isNull();
        assertThat(a.getSellPrice()).as("Latest: no batch price").isNull();
    }

    @Test
    @DisplayName("only the cost corrected (250 → 260): the batch gets the new cost AND the bill's new paid total, 10 × 260")
    void cost_only_reaches_the_batch_with_paid_total() {
        StockPurchaseAdjust a = PurchaseService.batchAdjustForEdit(saved(9963L), 0f, "T25791", EXP, new BigDecimal("250.00"), null);
        assertThat(a).isNotNull();
        assertThat(a.getPurchasePrice()).isEqualByComparingTo("260.00");
        assertThat(a.getPaidTotal()).isEqualByComparingTo("2600.00");
    }

    @Test
    @DisplayName("same cost written differently (260 vs 260.00) is NOT a change")
    void scale_is_not_a_change() {
        assertThat(PurchaseService.batchAdjustForEdit(saved(9963L), 0f, "T25791", EXP, new BigDecimal("260"), null)).isNull();
    }

    @Test
    @DisplayName("batch number corrected: newBatchNo carries it; the old number is still sent for the search fallback")
    void rename_reaches_the_batch() {
        StockPurchaseAdjust a = PurchaseService.batchAdjustForEdit(saved(9963L), 0f, "T2579", EXP, RATE, null);
        assertThat(a.getNewBatchNo()).isEqualTo("T25791");
        assertThat(a.getBatchNo()).isEqualTo("T2579");
    }

    @Test
    @DisplayName("blank and null batch numbers are the same — no rename, nothing sent")
    void blank_is_not_a_rename() {
        Purchase p = saved(9963L);
        p.setBatchNo("  ");
        assertThat(PurchaseService.batchAdjustForEdit(p, 0f, null, EXP, RATE, null)).isNull();
    }

    @Test
    @DisplayName("an OLDER bill (no stockEntryId): an expiry-only edit sends nothing — by number the batch could be another bill's")
    void older_bill_unchanged_behaviour() {
        assertThat(PurchaseService.batchAdjustForEdit(saved(null), 0f, "T25791", LocalDate.of(2027, 9, 9), RATE, null)).isNull();
    }

    @Test
    @DisplayName("an OLDER bill with a quantity change sends exactly what it always sent: no id, no paid total, no rename")
    void older_bill_quantity_change_as_before() {
        StockPurchaseAdjust a = PurchaseService.batchAdjustForEdit(saved(null), 2f, "T25791", EXP, RATE, null);
        assertThat(a.getDelta()).isEqualTo(2f);
        assertThat(a.getStockEntryId()).isNull();
        assertThat(a.getPaidTotal()).isNull();
        assertThat(a.getNewBatchNo()).isNull();
    }

    @Test
    @DisplayName("a bill that knows its batch, quantity changed: its id and the new paid total go with the delta")
    void own_bill_quantity_change_restamps_cost() {
        Purchase p = saved(9963L);
        p.setQuantity(12f);
        PurchaseService.stampPaidTotal(p);
        StockPurchaseAdjust a = PurchaseService.batchAdjustForEdit(p, 2f, "T25791", EXP, RATE, null);
        assertThat(a.getStockEntryId()).isEqualTo(9963L);
        assertThat(a.getPaidTotal()).isEqualByComparingTo("3120.00");
    }

    @Test
    @DisplayName("Per batch re-price with nothing else changed still goes (PR-3b unchanged)")
    void per_batch_reprice_still_sent() {
        StockPurchaseAdjust a = PurchaseService.batchAdjustForEdit(saved(9963L), 0f, "T25791", EXP, RATE, new BigDecimal("300.00"));
        assertThat(a.getSellPrice()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("stampPaidTotal: rate × billed quantity; nothing without a rate")
    void paid_total_rule() {
        Purchase p = new Purchase();
        p.setQuantity(10f);
        PurchaseService.stampPaidTotal(p);
        assertThat(p.getPaidTotal()).isNull();
        p.setBpurchaseRate(new BigDecimal("270.00"));
        PurchaseService.stampPaidTotal(p);
        assertThat(p.getPaidTotal()).isEqualByComparingTo("2700.00");
    }
}

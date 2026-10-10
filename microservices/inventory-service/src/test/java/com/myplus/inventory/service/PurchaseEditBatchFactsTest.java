package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.myplus.commerce.contracts.dto.StockPurchaseAdjust;
import com.myplus.inventory.entity.StockEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-3 — what an edited bill now says about ITS batch is written to it, whatever the delta. Consumption costs a unit at
 * paidTotal ÷ receivedQuantity (COGS-1), so the two move together. Design: selling-price-per-purchase-analysis.md §12.6.
 */
class PurchaseEditBatchFactsTest {

    private static StockEntry batch() {
        return StockEntry.builder().productId(11064L).batchNo("T25791").expiryDate(LocalDate.of(2027, 10, 9))
                .quantity(new BigDecimal("10")).receivedQuantity(new BigDecimal("10")).paidTotal(new BigDecimal("2600.00"))
                .purchasePrice(new BigDecimal("260.00")).build();
    }

    @Test
    @DisplayName("bill 3106: an expiry-only edit (delta 0) moves the batch's expiry, and nothing else")
    void expiry_only() {
        StockEntry e = batch();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).expiryDate(LocalDate.of(2027, 9, 9))
                .purchasePrice(new BigDecimal("260.00")).paidTotal(new BigDecimal("2600.00")).build());
        assertThat(e.getExpiryDate()).isEqualTo(LocalDate.of(2027, 9, 9));
        assertThat(e.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(e.getPaidTotal()).isEqualByComparingTo("2600.00");
        assertThat(e.getBatchNo()).isEqualTo("T25791");
    }

    @Test
    @DisplayName("a cost correction 260 → 250 with the quantity kept: 10 billed units at 250 = 2500, unit cost 250")
    void cost_only_rederives_paid_total() {
        StockEntry e = batch();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).purchasePrice(new BigDecimal("250.00")).build());
        assertThat(e.getPurchasePrice()).isEqualByComparingTo("250.00");
        assertThat(e.getPaidTotal()).isEqualByComparingTo("2500.00");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("quantity 10 → 12 at 260: billed and received both move by 2, unit cost stays 3120 ÷ 12 = 260")
    void quantity_change_moves_received_with_paid() {
        StockEntry e = batch();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(2f).purchasePrice(new BigDecimal("260.00")).build());
        assertThat(e.getReceivedQuantity()).isEqualByComparingTo("12");
        assertThat(e.getPaidTotal()).isEqualByComparingTo("3120.00");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("260");
    }

    @Test
    @DisplayName("TP-5: AFTER A RETURN (bill now 8, batch still paid 1000 for 10) an edit keeps 100 a unit — never 800 ÷ 10")
    void edit_after_a_return_keeps_the_unit_cost() {
        StockEntry e = StockEntry.builder().productId(1L).quantity(new BigDecimal("8")).receivedQuantity(new BigDecimal("10"))
                .paidTotal(new BigDecimal("1000.00")).purchasePrice(new BigDecimal("100.00")).build();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).expiryDate(LocalDate.of(2028, 1, 1))
                .purchasePrice(new BigDecimal("100.00")).paidTotal(new BigDecimal("800.00")).build());   // a bill-side 800 is ignored
        assertThat(e.getPaidTotal()).isEqualByComparingTo("1000.00");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("100");
        // and a cost correction after the return: 10 billed at 90
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).purchasePrice(new BigDecimal("90.00")).build());
        assertThat(e.getPaidTotal()).isEqualByComparingTo("900.00");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("a bonus batch (10 billed + 2 free = 12 received, 2600 paid): a cost edit to 200 → 10 × 200 over 12 units")
    void bonus_stays_in_the_divisor() {
        StockEntry e = batch();
        e.setReceivedQuantity(new BigDecimal("12"));
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).purchasePrice(new BigDecimal("200.00")).build());
        assertThat(e.getReceivedQuantity()).isEqualByComparingTo("12");
        assertThat(e.getPaidTotal()).isEqualByComparingTo("2000.00");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("166.666667");
    }

    @Test
    @DisplayName("a batch number correction renames it; blank leaves it")
    void rename() {
        StockEntry e = batch();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).newBatchNo("  ").build());
        assertThat(e.getBatchNo()).isEqualTo("T25791");
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).newBatchNo(" T25799 ").build());
        assertThat(e.getBatchNo()).isEqualTo("T25799");
    }

    @Test
    @DisplayName("absent fields leave the batch exactly as it was (an older caller sends none of them)")
    void absent_fields_change_nothing() {
        StockEntry e = batch();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).build());
        assertThat(e.getExpiryDate()).isEqualTo(LocalDate.of(2027, 10, 9));
        assertThat(e.getPaidTotal()).isEqualByComparingTo("2600.00");
        assertThat(e.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(e.getPurchasePrice()).isEqualByComparingTo("260.00");
    }

    @Test
    @DisplayName("a batch with no received quantity (pre-V12) keeps costing from its unit price: neither figure is touched")
    void no_received_quantity_untouched() {
        StockEntry e = batch();
        e.setReceivedQuantity(null);
        e.setPaidTotal(null);
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).purchasePrice(new BigDecimal("250.00")).build());
        assertThat(e.getPaidTotal()).isNull();
        assertThat(e.getReceivedQuantity()).isNull();
    }
}

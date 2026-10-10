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
    @DisplayName("a cost correction 260 → 250 with the quantity kept: unit cost becomes 2500 ÷ 10 = 250")
    void cost_only_restamps_paid_total() {
        StockEntry e = batch();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f)
                .purchasePrice(new BigDecimal("250.00")).paidTotal(new BigDecimal("2500.00")).build());
        assertThat(e.getPurchasePrice()).isEqualByComparingTo("250.00");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("quantity 10 → 12 at 260: received moves by the SAME delta, so the unit cost stays 3120 ÷ 12 = 260")
    void quantity_change_moves_received_with_paid() {
        StockEntry e = batch();
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(2f).paidTotal(new BigDecimal("3120.00")).build());
        assertThat(e.getReceivedQuantity()).isEqualByComparingTo("12");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("260");
    }

    @Test
    @DisplayName("a bonus batch (10 billed + 2 free = 12 received, 2600 paid): a cost edit keeps the bonus in the divisor")
    void bonus_stays_in_the_divisor() {
        StockEntry e = batch();
        e.setReceivedQuantity(new BigDecimal("12"));
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).paidTotal(new BigDecimal("2400.00")).build());
        assertThat(e.getReceivedQuantity()).isEqualByComparingTo("12");
        assertThat(ReservationService.unitCostOf(e)).isEqualByComparingTo("200");
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
        StockService.applyBillFacts(e, StockPurchaseAdjust.builder().delta(0f).paidTotal(new BigDecimal("2500.00")).build());
        assertThat(e.getPaidTotal()).isNull();
        assertThat(e.getReceivedQuantity()).isNull();
    }
}

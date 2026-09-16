package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.myplus.inventory.entity.StockEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * COGS-1 — what one unit of a batch cost, as a rule on its own. Pure, no database: runs on every {@code mvn test}.
 *
 * <p>The defect: the cost was {@code paidTotal / quantity}, and {@code quantity} is what is LEFT. Every sale after the
 * first from a batch was costed higher than the last. The real-database version of this, through reserve and confirm,
 * is {@code ReservationServiceTest.a_batch_costs_the_same_per_unit_on_every_sale}.
 */
class BatchUnitCostTest {

    private static StockEntry batch(String paid, String received, String left, String price) {
        return StockEntry.builder()
                .paidTotal(paid == null ? null : new BigDecimal(paid))
                .receivedQuantity(received == null ? null : new BigDecimal(received))
                .quantity(new BigDecimal(left))
                .purchasePrice(price == null ? null : new BigDecimal(price))
                .build();
    }

    @Test
    @DisplayName("⭐ a batch partly sold still costs what it cost — 800 for 10 is 80.00 with 8 left, not 100.00")
    void cost_does_not_rise_as_the_batch_sells_down() {
        assertThat(ReservationService.unitCostOf(batch("800.00", "10", "10", "80")))
                .isEqualByComparingTo("80");
        assertThat(ReservationService.unitCostOf(batch("800.00", "10", "8", "80")))
                .as("the defect answered 100.00 here — 800 / the 8 still on the shelf")
                .isEqualByComparingTo("80");
        assertThat(ReservationService.unitCostOf(batch("800.00", "10", "0.5", "80")))
                .as("and 1,600.00 with half a pack left")
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("a supplier bonus is allocated over EVERY unit received — 5,000 for 11 is 454.545455, not 500")
    void a_bonus_lowers_the_unit_cost() {
        assertThat(ReservationService.unitCostOf(batch("5000.00", "11", "11", "500")))
                .isEqualByComparingTo("454.545455");
        assertThat(ReservationService.unitCostOf(batch("5000.00", "11", "3", "500")))
                .as("and stays there however few are left")
                .isEqualByComparingTo("454.545455");
    }

    @Test
    @DisplayName("a batch with no paid total costs from its purchase price, as it always did")
    void legacy_batch_uses_purchase_price() {
        assertThat(ReservationService.unitCostOf(batch(null, null, "4", "80")))
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("⚠ a paid total with no received quantity costs from the purchase price — never from what is left")
    void missing_received_quantity_never_falls_back_to_the_remaining_stock() {
        assertThat(ReservationService.unitCostOf(batch("800.00", null, "8", "80")))
                .as("dividing by the 8 left is the defect; the billed rate is the safe answer")
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("no batch, no cost")
    void no_batch() {
        assertThat(ReservationService.unitCostOf(null)).isNull();
    }
}

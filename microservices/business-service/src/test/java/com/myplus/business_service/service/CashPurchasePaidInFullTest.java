package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.business_service.entity.Purchase;

/** FP-6a guard — a purchase with no supplier is a cash purchase: paid in full, nothing owed to nobody. */
class CashPurchasePaidInFullTest {

    private static Purchase purchase(Long vender, String paid, String bill) {
        Purchase p = new Purchase();
        p.setVenderId(vender);
        p.setPaidAmount(new BigDecimal(paid));
        p.setDueAmount(new BigDecimal(paid).subtract(new BigDecimal(bill)));
        return p;
    }

    @Test
    @DisplayName("⭐ the found case: no supplier, 200 typed on a 220 bill (tax on) → paid 220, owes 0")
    void taxedCashPurchaseIsPaidInFull() {
        Purchase p = purchase(null, "200", "220");
        PurchaseService.cashPurchasePaidInFull(p, new BigDecimal("220"));
        assertThat(p.getPaidAmount()).isEqualByComparingTo("220");
        assertThat(p.getDueAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("no supplier, overpaid → paid becomes the bill (no advance held for nobody)")
    void overpaidCashPurchase() {
        Purchase p = purchase(null, "250", "220");
        PurchaseService.cashPurchasePaidInFull(p, new BigDecimal("220"));
        assertThat(p.getPaidAmount()).isEqualByComparingTo("220");
        assertThat(p.getDueAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("WITH a supplier, credit stays credit: 0 paid on 220 still owes 220")
    void supplierPurchaseUntouched() {
        Purchase p = purchase(7L, "0", "220");
        PurchaseService.cashPurchasePaidInFull(p, new BigDecimal("220"));
        assertThat(p.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(p.getDueAmount()).isEqualByComparingTo("-220");
    }
}

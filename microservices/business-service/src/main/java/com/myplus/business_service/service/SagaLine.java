package com.myplus.business_service.service;

import java.math.BigDecimal;

/**
 * A resolved saga sell line (slice 33, U3b): the catalog product to sell, quantity, and the catalog-derived
 * sell rate (D1) plus the amounts carried from the request. G3 (slice 35) adds the applied tax: {@code taxRate}
 * (%), {@code taxAmount} for the line, and {@code lineGross} (= taxable net + tax) the customer pays.
 */
public record SagaLine(
        Long productId,
        Float quantity,
        BigDecimal sellRate,
        BigDecimal discount,
        BigDecimal totalAmount,
        BigDecimal netAmount,
        BigDecimal srp,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal lineGross,
        BigDecimal catalogPrice,
        String discountType,     // display type of the applied discount ("%" or "Amount"); persisted to Sell.dt
        BigDecimal costPrice,    // SF-10: unit COGS snapshot (latest purchase rate) for per-line margin
        String priceReason,      // B2B-P2 (#10): why this price applied; null when priced at catalog
        Float bonusQuantity) {   // B2B-P3g: free goods on this line ("Bon."); presentation only — see D-2

    /*
     * NOTE for whoever widens this record next: every component is spelled out positionally in
     * MarginPolicyTest's `line()` helper, so adding one here BREAKS that test's compilation. It has happened
     * three times now (SF-10 costPrice, B2B-P2 priceReason, and this). Update the helper in the same commit —
     * a test that does not compile is a test that does not run, and business-service's unit suite silently
     * did not run for three phases the last time that was missed.
     */
}

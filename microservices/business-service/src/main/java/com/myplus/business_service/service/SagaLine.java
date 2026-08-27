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
        Float bonusQuantity,     // B2B-P3g: free goods on this line ("Bon."); presentation only — see D-2

        /*
         * U2 — the broken-pack record. Design: docs/slices/u2-loose-sale-arithmetic.md §3.
         *
         * `quantity` above stays in SELLING units (0.5 of a pack) and `sellRate` per selling unit, because
         * `total = quantity x rate` is the identity every report sums. These four carry the sale as the
         * CUSTOMER experienced it — 5 tablets at 12.00 out of a pack of 10 — and nothing prices from them.
         *
         * All four are null on an ordinary line, which is every line until a shop switches loose selling on.
         */
        String soldUnit,         // "LOOSE" or null; PACK is written only when a caller says so explicitly
        Float soldQuantity,      // pieces
        BigDecimal soldRate,     // per piece, for the receipt
        Integer packSizeSnapshot) {   // frozen — §3.2: a later pack-size edit must not re-interpret this sale

    /*
     * NOTE for whoever widens this record next: every component is spelled out positionally in
     * MarginPolicyTest's `line()` helper, so adding one here BREAKS that test's compilation. It has happened
     * four times now (SF-10 costPrice, B2B-P2 priceReason, B2B-P3g bonusQuantity, and U2's four). Update the
     * helper in the same commit —
     * a test that does not compile is a test that does not run, and business-service's unit suite silently
     * did not run for three phases the last time that was missed.
     */
}

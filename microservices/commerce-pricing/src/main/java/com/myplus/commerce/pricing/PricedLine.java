package com.myplus.commerce.pricing;

import java.math.BigDecimal;

/**
 * What one line actually costs, and WHY.
 *
 * <p>The reason is not decoration. Today a trade customer's price is a number a cashier typed, and nothing
 * records why it was that number — which is exactly what makes a disputed invoice unanswerable. Every priced
 * line carries the rule that set it.
 *
 * @param productId the line's product
 * @param unitPrice the resolved unit price
 * @param source    {@code CONTRACT} (a named customer's rule), {@code TIER} (their customer type's rule),
 *                  or {@code CATALOG} (no rule applied)
 * @param ruleId    the rule that decided it; null when the source is CATALOG
 * @param reason    a short human-readable explanation for the receipt/screen, e.g. "Wholesale -12%"
 */
public record PricedLine(Long productId, BigDecimal unitPrice, String source, Long ruleId, String reason) {

    public static final String CONTRACT = "CONTRACT";
    public static final String TIER = "TIER";
    public static final String CATALOG = "CATALOG";

    /** The unchanged catalog price — used when no rule matches, and when the pricing service cannot be reached. */
    public static PricedLine catalog(Long productId, BigDecimal catalogPrice) {
        return new PricedLine(productId, catalogPrice, CATALOG, null, null);
    }

    public boolean isFromRule() {
        return ruleId != null;
    }
}

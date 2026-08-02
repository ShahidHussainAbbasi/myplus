package com.myplus.commerce.pricing;

import java.math.BigDecimal;

/**
 * One line to be priced: what it is, how many, and what the catalog says it costs.
 *
 * <p>{@code catalogPrice} is passed in rather than looked up, because the caller has already loaded the
 * product — and because it is the fallback when no rule matches, so the resolver can always answer.
 *
 * @param productId    the product being priced
 * @param categoryId   its category, for category-scoped rules; null if uncategorised
 * @param quantity     how many (carried through for future quantity-break rules; not used by the current
 *                     precedence, and deliberately not invented before it is asked for)
 * @param catalogPrice the unit price with no rule applied — the answer when nothing matches
 */
public record BasketLine(Long productId, Long categoryId, BigDecimal quantity, BigDecimal catalogPrice) {
}

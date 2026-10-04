package com.myplus.commerce.pricing;

import java.time.LocalDate;

/**
 * Who is buying, and when — everything the resolver needs about the buyer.
 *
 * <p>{@code customerType} is Phase 0's {@code Customer.customerType} (WALK_IN / RETAILER / WHOLESALE / VIP).
 * That field was built as the key the rest of the B2B work would hang off; tier pricing is what finally uses
 * it.
 *
 * <p>The date is passed IN rather than read from a clock so the resolver stays pure and its date-boundary
 * behaviour is testable without freezing time.
 *
 * @param customerId   the identified customer, or null for a walk-in with no account
 * @param customerType the tier, or null when unknown
 * @param on           the date to judge rule validity against; null means "ignore dates"
 */
public record PricingContext(Long customerId, String customerType, LocalDate on) {
    // TZ-2: no "of(customer, type)" defaulting to LocalDate.now() — that is the SERVER's UTC day. The caller passes
    // the business day (TenantClock.today() in a service) as {@code on}.
}

package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.function.Predicate;

/**
 * The report's filters as ONE object (slice b2b-P3e-1 = requirement #6) — a Query Object, so adding a
 * dimension never means another parameter on every signature between the controller and the query.
 *
 * <p><b>Every field is optional, and absent means "don't narrow on this".</b> An empty filter therefore
 * reproduces today's report exactly, which is what lets this ship to live tenants without changing what any
 * existing user sees.
 *
 * <p><b>These filters NARROW an already org-scoped result.</b> They can never widen it: the date-range query
 * they refine is scoped by organization, and nothing here touches that. A filter parameter must not become a
 * way to read another tenant's sales.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleReportFilter {

    private Long customerId;
    private Long productId;
    private String category;
    /** WALK_IN | RETAIL | WHOLESALE | RETAILER — the B2B/B2C channel from Phase 0. */
    private String customerType;

    public boolean isEmpty() {
        return customerId == null && productId == null
                && isBlank(category) && isBlank(customerType);
    }

    /**
     * The filter as a predicate over enriched report rows.
     *
     * <p>Applied after the date-range query and the catalog enrichment, because category and customer type
     * live on the enriched row rather than on {@code Sell} — pushing them into SQL would mean joining the
     * catalog inside a query that deliberately does not know about it.
     */
    public Predicate<SellDTO> asPredicate() {
        return row -> matchesCustomer(row) && matchesProduct(row)
                && matchesCategory(row) && matchesCustomerType(row);
    }

    private boolean matchesCustomer(SellDTO row) {
        return customerId == null || customerId.equals(row.getCustomerId());
    }

    private boolean matchesProduct(SellDTO row) {
        return productId == null || productId.equals(row.getProductId());
    }

    private boolean matchesCategory(SellDTO row) {
        return isBlank(category) || category.equalsIgnoreCase(row.getCategory());
    }

    private boolean matchesCustomerType(SellDTO row) {
        return isBlank(customerType) || customerType.equalsIgnoreCase(row.getCustomerType());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

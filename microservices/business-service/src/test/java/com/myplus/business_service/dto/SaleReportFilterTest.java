package com.myplus.business_service.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2B-P3e-1 (#6): the report filter is a pure Query Object, so its rules are testable without Spring, a
 * database or a request. Runs on every {@code mvn test}.
 */
class SaleReportFilterTest {

    private SellDTO row(Long customerId, Long productId, String category, String customerType) {
        SellDTO r = new SellDTO();
        r.setCustomerId(customerId);
        r.setProductId(productId);
        r.setCategory(category);
        r.setCustomerType(customerType);
        return r;
    }

    @Test
    @DisplayName("an empty filter changes nothing — today's report is unaffected")
    void emptyFilterMatchesEverything() {
        // THE live-modules guarantee: a tenant who never touches a filter sees exactly what they see now.
        SaleReportFilter f = SaleReportFilter.builder().build();
        assertTrue(f.isEmpty());
        assertTrue(f.asPredicate().test(row(1L, 2L, "Drinks", "WALK_IN")));
        assertTrue(f.asPredicate().test(row(null, null, null, null)));
    }

    @Test
    @DisplayName("filtering by customer keeps only that customer's lines")
    void byCustomer() {
        SaleReportFilter f = SaleReportFilter.builder().customerId(7L).build();
        assertTrue(f.asPredicate().test(row(7L, 2L, "Drinks", "RETAIL")));
        assertFalse(f.asPredicate().test(row(8L, 2L, "Drinks", "RETAIL")));
        assertFalse(f.asPredicate().test(row(null, 2L, "Drinks", "RETAIL")), "a line with no customer is not customer 7");
    }

    @Test
    @DisplayName("filters COMBINE — every one must match, not any")
    void filtersAreAnded() {
        // "What did customer 7 buy in Drinks" is one question, not two.
        SaleReportFilter f = SaleReportFilter.builder().customerId(7L).category("Drinks").build();
        assertTrue(f.asPredicate().test(row(7L, 2L, "Drinks", "RETAIL")));
        assertFalse(f.asPredicate().test(row(7L, 2L, "Snacks", "RETAIL")), "right customer, wrong category");
        assertFalse(f.asPredicate().test(row(9L, 2L, "Drinks", "RETAIL")), "right category, wrong customer");
    }

    @Test
    @DisplayName("category and channel match case-insensitively")
    void caseInsensitive() {
        // The value arrives from a dropdown or a typed URL; case must not silently empty the report.
        assertTrue(SaleReportFilter.builder().category("drinks").build()
                .asPredicate().test(row(1L, 2L, "Drinks", "RETAIL")));
        assertTrue(SaleReportFilter.builder().customerType("wholesale").build()
                .asPredicate().test(row(1L, 2L, "Drinks", "WHOLESALE")));
    }

    @Test
    @DisplayName("a blank string is not a filter")
    void blankIsNotAFilter() {
        // An untouched dropdown posts "" — that must mean "all", never "match rows whose category is empty".
        SaleReportFilter f = SaleReportFilter.builder().category("").customerType("   ").build();
        assertTrue(f.isEmpty());
        assertTrue(f.asPredicate().test(row(1L, 2L, "Drinks", "RETAIL")));
    }

    @Test
    @DisplayName("filtering by channel separates B2B from B2C")
    void byChannel() {
        SaleReportFilter f = SaleReportFilter.builder().customerType("WHOLESALE").build();
        assertTrue(f.asPredicate().test(row(1L, 2L, "Drinks", "WHOLESALE")));
        assertFalse(f.asPredicate().test(row(1L, 2L, "Drinks", "WALK_IN")));
    }
}

package com.myplus.catalog.service;

import com.myplus.catalog.entity.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-rate tax: a product's exposed rate resolves from its assigned tax-code (so a rate change to the code
 * propagates to every product), falling back to the legacy per-product rate when no code is assigned or the code is
 * gone. Pure logic — no Spring/DB. Guards that {@code ProductRef.taxRate} (the sale hot path) stays correct.
 */
class TaxCodeResolutionTest {

    private static final Map<Long, BigDecimal> RATES = Map.of(
            10L, new BigDecimal("18.00"),   // Standard
            20L, new BigDecimal("5.00"));   // Reduced

    private static Product product(Long taxCodeId, String legacyRate) {
        return Product.builder().id(1L)
                .taxCodeId(taxCodeId)
                .taxRate(legacyRate == null ? null : new BigDecimal(legacyRate))
                .build();
    }

    @Test void assignedCodeWins() {
        assertEquals(new BigDecimal("18.00"), ProductService.resolveRate(product(10L, "99"), RATES));
        assertEquals(new BigDecimal("5.00"), ProductService.resolveRate(product(20L, null), RATES));
    }

    @Test void noCodeFallsBackToLegacyRate() {
        assertEquals(new BigDecimal("12.00"), ProductService.resolveRate(product(null, "12.00"), RATES));
    }

    @Test void danglingCodeFallsBackToLegacyRate() {
        // code id not in the org's map (deleted) → don't fail, use the product's own rate
        assertEquals(new BigDecimal("7.00"), ProductService.resolveRate(product(999L, "7.00"), RATES));
    }

    @Test void noCodeAndNoLegacyRateIsNull() {
        // → downstream (business TaxService) applies the org default
        assertNull(ProductService.resolveRate(product(null, null), RATES));
    }
}

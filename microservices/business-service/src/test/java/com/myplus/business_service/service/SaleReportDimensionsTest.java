package com.myplus.business_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.commerce.contracts.dto.ProductRef;

/** SRA-4a — free goods are valued at TP (the product's last purchase rate), the stock-sales report's convention. */
class SaleReportDimensionsTest {

    private static ProductRef tp(String rate) {
        ProductRef p = new ProductRef();
        p.setLastPurchaseRate(rate == null ? null : new BigDecimal(rate));
        return p;
    }

    @Test
    @DisplayName("bonus value = bonus qty × TP, to the cent; zero with no bonus, no TP, or no product")
    void bonusValue() {
        assertEquals(new BigDecimal("300.00"), SaleReportDimensions.bonusValue(new BigDecimal("3"), tp("100")));
        assertEquals(new BigDecimal("37.50"), SaleReportDimensions.bonusValue(new BigDecimal("1.5"), tp("25")));
        assertEquals(new BigDecimal("0.00"), SaleReportDimensions.bonusValue(null, tp("100")));
        assertEquals(new BigDecimal("0.00"), SaleReportDimensions.bonusValue(BigDecimal.ZERO, tp("100")));
        assertEquals(new BigDecimal("0.00"), SaleReportDimensions.bonusValue(new BigDecimal("3"), tp(null)), "never bought: no TP yet");
        assertEquals(new BigDecimal("0.00"), SaleReportDimensions.bonusValue(new BigDecimal("3"), null));
    }
}

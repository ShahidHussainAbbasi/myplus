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
        BigDecimal lineGross) {
}

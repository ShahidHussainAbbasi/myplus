package com.myplus.business_service.service;

import java.math.BigDecimal;

/**
 * A resolved saga sell line (slice 33, U3b): the catalog product to sell, quantity, and the catalog-derived
 * sell rate (D1) plus the amounts carried from the request.
 */
public record SagaLine(
        Long productId,
        Float quantity,
        BigDecimal sellRate,
        BigDecimal discount,
        BigDecimal totalAmount,
        BigDecimal netAmount,
        BigDecimal srp) {
}

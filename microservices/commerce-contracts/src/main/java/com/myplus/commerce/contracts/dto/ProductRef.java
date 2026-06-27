package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Lightweight cross-service reference to a catalog product (slice 33). Domains that need to display, link, or
 * price a product (trade line items, pharmacy medicines) carry this instead of duplicating the catalog entity.
 * Carries {@code sellingPrice}/{@code taxRate} so the sell saga can price from catalog (D1).
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class ProductRef {
    private Long id;
    private String sku;
    private String name;
    private String unit;
    private BigDecimal sellingPrice;
    private BigDecimal taxRate;
}

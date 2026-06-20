package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight cross-service reference to a catalog product (slice 33). Domains that need to display or link
 * a product (trade line items, pharmacy medicines) carry this instead of duplicating the full catalog entity.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class ProductRef {
    private Long id;
    private String sku;
    private String name;
    private String unit;
}

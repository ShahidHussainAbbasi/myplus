package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One row of a bulk product import (slice 33, U2). Cross-service contract: business-service builds these from
 * its Items and POSTs them to catalog-service. {@code clientRef} is the source itemId, echoed back in the
 * result so the caller can build its itemId→productId map. {@code categoryName} is resolved find-or-create.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductImportLine {
    private Long clientRef;
    private String sku;
    private String name;
    private String description;
    private String unit;
    private String manufacturer;
    private String categoryName;
    private BigDecimal sellingPrice;
    private BigDecimal taxRate;
}

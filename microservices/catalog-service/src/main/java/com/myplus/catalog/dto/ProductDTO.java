package com.myplus.catalog.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductDTO {
    private Long id;
    private String sku;
    private String barcode;     // barcode-first sell: scannable EAN/UPC (distinct from sku)
    private String name;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String unit;
    private String manufacturer;
    private BigDecimal sellingPrice;
    private BigDecimal taxRate;
    private Long taxCodeId;         // multi-rate tax: assigned tax-code (null = use taxRate / org default)
    private String taxCodeName;     // read-only, for display in the product list/form
    private Boolean isActive;
    private String imageUrl;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.myplus.inventory.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductDTO {
    private Long id;
    private String sku;
    private String name;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String unit;
    private Float minStockLevel;
    private Float maxStockLevel;
    private Float reorderPoint;
    private Float currentStock;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal taxRate;
    private Boolean isActive;
    private String imageUrl;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

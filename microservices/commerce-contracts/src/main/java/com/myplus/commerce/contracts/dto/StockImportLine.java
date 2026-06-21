package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Opening stock for a migrated product (slice 33, U2b). business-service builds these from its local Stock
 * and POSTs them to inventory-service, which creates a StockLevel + an opening StockEntry for the productId.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StockImportLine {
    private Long productId;
    private Float quantity;
    private String batchNo;
    private LocalDate expiryDate;
    private BigDecimal purchasePrice;
    private BigDecimal costPrice;
}

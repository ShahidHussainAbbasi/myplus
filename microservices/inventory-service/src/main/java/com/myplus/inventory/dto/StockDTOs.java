package com.myplus.inventory.dto;

import com.myplus.inventory.entity.StockAdjustment;
import com.myplus.inventory.entity.StockTransfer;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class StockDTOs {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockEntryDTO {
        private Long id;
        private Long productId;
        private Long warehouseId;
        private Float quantity;
        private String batchNo;
        private String lotNo;
        private LocalDate expiryDate;
        private BigDecimal purchasePrice;
        private LocalDateTime entryDate;
        private Long supplierId;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockAdjustmentDTO {
        private Long id;
        private Long productId;
        private Long warehouseId;
        private StockAdjustment.AdjustmentType adjustmentType;
        private Float quantity;
        private String reason;
        private Long adjustedBy;
        private LocalDateTime adjustedAt;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockTransferDTO {
        private Long id;
        private Long fromWarehouseId;
        private Long toWarehouseId;
        private Long productId;
        private Float quantity;
        private LocalDateTime transferDate;
        private Long transferredBy;
        private StockTransfer.TransferStatus status;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockSummaryDTO {
        private Long totalProducts;
        private Long lowStockCount;
        private Long outOfStockCount;
        private BigDecimal totalInventoryValue;
    }
}

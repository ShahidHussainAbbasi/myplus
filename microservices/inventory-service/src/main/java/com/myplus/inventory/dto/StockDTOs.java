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
        /** IGNORED on a write (BLK-5): the server stamps the authenticated caller, never trusting the body. */
        private Long adjustedBy;
        private LocalDateTime adjustedAt;
        private String notes;
        /** BLK-5 — the caller's key for ONE intended correction; a repeat replays instead of adjusting twice. */
        private String idempotencyKey;
    }

    /**
     * BLK-5 — what a stock correction ANSWERS with, and what its history lists.
     *
     * <p>A DTO, not the entity: {@code StockAdjustment.warehouse} is LAZY, and a replayed row is read outside any
     * session, so serialising the entity would fail on the proxy the moment a warehouse is set.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockAdjustmentView {
        private Long id;
        private Long productId;
        private StockAdjustment.AdjustmentType adjustmentType;
        private BigDecimal quantity;
        private String reason;
        private Long adjustedBy;
        private LocalDateTime adjustedAt;
        private Long organizationId;
        private String idempotencyKey;
        /** The product's on-hand after this write — on a replay, as it is NOW (PERF-9: no read-back needed). */
        private Float resultingOnHand;
        /** True when this answer replays a correction the key had already recorded: nothing moved a second time. */
        private boolean replayed;

        public static StockAdjustmentView of(StockAdjustment a, Float onHand, boolean replayed) {
            return StockAdjustmentView.builder()
                    .id(a.getId())
                    .productId(a.getProductId())
                    .adjustmentType(a.getAdjustmentType())
                    .quantity(a.getQuantity())
                    .reason(a.getReason())
                    .adjustedBy(a.getAdjustedBy())
                    .adjustedAt(a.getAdjustedAt())
                    .organizationId(a.getOrganizationId())
                    .idempotencyKey(a.getIdempotencyKey())
                    .resultingOnHand(onHand)
                    .replayed(replayed)
                    .build();
        }
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

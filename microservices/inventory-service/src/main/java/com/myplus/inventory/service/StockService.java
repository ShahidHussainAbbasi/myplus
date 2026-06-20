package com.myplus.inventory.service;

import com.myplus.inventory.dto.StockDTOs.*;
import com.myplus.inventory.entity.*;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.inventory.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Stock operations (slice 33, Phase 5b). Quantity state lives in {@link StockLevel} (per product); product
 * master is in catalog-service (referenced by productId). Product-existence validation against catalog is
 * wired via CatalogClient in Phase 5c.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockLevelRepository stockLevelRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockTransferRepository stockTransferRepository;

    /** Find the caller's stock level for a product, or create a fresh zero level stamped to the tenant. */
    private StockLevel levelFor(Long productId, Long orgId, Long userId) {
        return stockLevelRepository.findByProductScoped(productId, orgId, userId)
                .orElseGet(() -> StockLevel.builder()
                        .productId(productId).currentStock(0f)
                        .organizationId(orgId).userId(userId).build());
    }

    @Transactional
    public StockEntry addStock(StockEntryDTO dto) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        Warehouse warehouse = dto.getWarehouseId() != null
                ? warehouseRepository.findByIdScoped(dto.getWarehouseId(), orgId, userId).orElse(null) : null;

        StockLevel level = levelFor(dto.getProductId(), orgId, userId);
        level.setCurrentStock((level.getCurrentStock() != null ? level.getCurrentStock() : 0f) + dto.getQuantity());
        stockLevelRepository.save(level);

        StockEntry entry = StockEntry.builder()
                .productId(dto.getProductId())
                .warehouse(warehouse)
                .quantity(dto.getQuantity())
                .batchNo(dto.getBatchNo())
                .lotNo(dto.getLotNo())
                .expiryDate(dto.getExpiryDate())
                .purchasePrice(dto.getPurchasePrice())
                .supplierId(dto.getSupplierId())
                .notes(dto.getNotes())
                .organizationId(orgId)
                .userId(userId)
                .build();
        return stockEntryRepository.save(entry);
    }

    @Transactional
    public StockAdjustment adjustStock(StockAdjustmentDTO dto) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        Warehouse warehouse = dto.getWarehouseId() != null
                ? warehouseRepository.findByIdScoped(dto.getWarehouseId(), orgId, userId).orElse(null) : null;

        StockLevel level = levelFor(dto.getProductId(), orgId, userId);
        float current = level.getCurrentStock() != null ? level.getCurrentStock() : 0f;
        switch (dto.getAdjustmentType()) {
            case INCREASE -> level.setCurrentStock(current + dto.getQuantity());
            case DECREASE -> {
                if (current < dto.getQuantity()) throw new ValidationException("Insufficient stock");
                level.setCurrentStock(current - dto.getQuantity());
            }
            case TRANSFER -> { /* handled via StockTransfer */ }
        }
        stockLevelRepository.save(level);

        StockAdjustment adj = StockAdjustment.builder()
                .productId(dto.getProductId())
                .warehouse(warehouse)
                .adjustmentType(dto.getAdjustmentType())
                .quantity(dto.getQuantity())
                .reason(dto.getReason())
                .adjustedBy(dto.getAdjustedBy())
                .notes(dto.getNotes())
                .build();
        return stockAdjustmentRepository.save(adj);
    }

    @Transactional
    public StockTransfer transferStock(StockTransferDTO dto) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        Warehouse from = warehouseRepository.findByIdScoped(dto.getFromWarehouseId(), orgId, userId)
                .orElseThrow(() -> new ValidationException("Source warehouse not found"));
        Warehouse to = warehouseRepository.findByIdScoped(dto.getToWarehouseId(), orgId, userId)
                .orElseThrow(() -> new ValidationException("Destination warehouse not found"));

        StockTransfer transfer = StockTransfer.builder()
                .productId(dto.getProductId())
                .fromWarehouse(from)
                .toWarehouse(to)
                .quantity(dto.getQuantity())
                .transferredBy(dto.getTransferredBy())
                .status(StockTransfer.TransferStatus.COMPLETED)
                .notes(dto.getNotes())
                .build();
        return stockTransferRepository.save(transfer);
    }

    public Float getCurrentStock(Long productId) {
        return stockLevelRepository.findByProductScoped(productId, CurrentUser.organizationId(), CurrentUser.userId())
                .map(StockLevel::getCurrentStock).orElse(0f);
    }

    public Page<StockEntry> getHistory(Long productId, Pageable pageable) {
        return stockEntryRepository.findByProductScoped(productId, CurrentUser.organizationId(), CurrentUser.userId(), pageable);
    }

    public StockSummaryDTO getSummary() {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        long totalProducts = stockLevelRepository.countScoped(orgId, userId);
        long lowStockCount = stockLevelRepository.findLowStockScoped(orgId, userId).size();
        long outOfStockCount = stockLevelRepository.findOutOfStockScoped(orgId, userId).size();
        BigDecimal totalValue = stockLevelRepository.findScoped(orgId, userId).stream()
                .filter(sl -> sl.getCurrentStock() != null && sl.getCostPrice() != null)
                .map(sl -> sl.getCostPrice().multiply(BigDecimal.valueOf(sl.getCurrentStock())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return StockSummaryDTO.builder()
                .totalProducts(totalProducts)
                .lowStockCount(lowStockCount)
                .outOfStockCount(outOfStockCount)
                .totalInventoryValue(totalValue)
                .build();
    }
}

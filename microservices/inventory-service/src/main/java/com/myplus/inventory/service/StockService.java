package com.myplus.inventory.service;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.StockBatch;
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
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
    private final CatalogClient catalogClient;

    /** Confirm the product exists in catalog before stock first enters inventory for it (anti-orphan).
     *  A 404 means "no such product"; other failures (catalog down) propagate — we don't mask them. */
    private void assertProductExists(Long productId) {
        try {
            catalogClient.getProduct(productId);
        } catch (HttpClientErrorException.NotFound nf) {
            throw new ValidationException("Product not found in catalog: " + productId);
        }
    }

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
        assertProductExists(dto.getProductId());   // catalog is the product system-of-record (Phase 5c)
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

    /** Batch on-hand for the whole tenant (slice 62, M3.1): productId → currentStock, in one query, so the Stock
     *  screen reads inventory without an HTTP call per item. */
    public java.util.Map<Long, Float> getAllLevels() {
        java.util.Map<Long, Float> out = new java.util.HashMap<>();
        for (StockLevel sl : stockLevelRepository.findScoped(CurrentUser.organizationId(), CurrentUser.userId())) {
            out.put(sl.getProductId(), sl.getCurrentStock() == null ? 0f : sl.getCurrentStock());
        }
        return out;
    }

    /** Quarantine register (slice 58): the org's non-sellable returned lots. */
    public java.util.Map<String, Object> listQuarantine() {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        List<java.util.Map<String, Object>> items = new ArrayList<>();
        for (StockEntry e : stockEntryRepository.findQuarantinedScoped(orgId, userId)) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", e.getId());
            m.put("productId", e.getProductId());
            m.put("batchNo", e.getBatchNo());
            m.put("expiryDate", e.getExpiryDate());
            m.put("quantity", e.getQuantity());
            items.add(m);
        }
        return java.util.Map.of("items", items);
    }

    /** Dispose a quarantined lot (slice 58) — destroyed / returned to supplier. Anti-IDOR: must be the caller's
     *  org AND actually quarantined. Returns true when a row was removed. */
    @Transactional
    public boolean disposeQuarantine(Long id) {
        StockEntry e = stockEntryRepository.findById(id).orElse(null);
        Long orgId = CurrentUser.organizationId();
        boolean mine = e != null && e.getOrganizationId() != null && e.getOrganizationId().equals(orgId);
        if (e == null || !mine || !Boolean.FALSE.equals(e.getRestockable())) return false;
        stockEntryRepository.delete(e);
        return true;
    }

    /** FEFO batches a sale/dispense draws from next (slice 54, P10): earliest-expiry first, expired excluded (G1),
     *  only batches with sellable qty (quantity − reserved > 0). Org-scoped via CurrentUser. */
    public List<StockBatch> getFefoBatches(Long productId) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        List<StockBatch> out = new ArrayList<>();
        for (StockEntry e : stockEntryRepository.findForFefo(productId, orgId, userId, LocalDate.now())) {
            float qty = e.getQuantity() == null ? 0f : e.getQuantity();
            float reserved = e.getReservedQuantity() == null ? 0f : e.getReservedQuantity();
            float available = qty - reserved;
            if (available <= 0f) continue;
            out.add(new StockBatch(productId, e.getBatchNo(), e.getExpiryDate(), BigDecimal.valueOf(available), e.getPurchasePrice()));
        }
        return out;
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

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

    /** Stock screen honesty (sellable + expired badge): per product → {onHand, sellable, expired} in one scoped
     *  pass. onHand = physical StockLevel.currentStock; sellable = what the FEFO allocator can actually hold
     *  (non-expired, non-quarantined, minus holds); expired = physical qty stuck in expired batches. This lets the
     *  Product screen show the true "you can sell N" number instead of a raw on-hand that overstates it (a product
     *  can read 16 on-hand yet be 0 sellable when every batch has expired). One call for the whole list. */
    public java.util.Map<Long, java.util.Map<String, Float>> getLevelDetail() {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.Map<Long, java.util.Map<String, Float>> out = new java.util.HashMap<>();
        // Seed every product that has a StockLevel row with its physical on-hand (sellable/expired default 0).
        for (StockLevel sl : stockLevelRepository.findScoped(orgId, userId)) {
            java.util.Map<String, Float> m = new java.util.HashMap<>();
            m.put("onHand", sl.getCurrentStock() == null ? 0f : sl.getCurrentStock());
            m.put("sellable", 0f);
            m.put("expired", 0f);
            out.put(sl.getProductId(), m);
        }
        // Overlay the batch-derived sellable/expired split.
        for (Object[] row : stockEntryRepository.sellableExpiredByScope(orgId, userId, today)) {
            Long pid = (Long) row[0];
            float sellable = row[1] == null ? 0f : ((Number) row[1]).floatValue();
            float expired = row[2] == null ? 0f : ((Number) row[2]).floatValue();
            java.util.Map<String, Float> m = out.computeIfAbsent(pid, k -> {
                java.util.Map<String, Float> mm = new java.util.HashMap<>();
                mm.put("onHand", 0f);
                return mm;
            });
            m.put("sellable", Math.max(0f, sellable));
            m.put("expired", Math.max(0f, expired));
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

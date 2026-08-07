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

        // Route through applyStockDelta so a product-screen +/− adjust moves the BATCHES too (not just the scalar
        // on-hand) — keeping master data consistent no matter which side edited it.
        float qty = dto.getQuantity() != null ? dto.getQuantity() : 0f;
        switch (dto.getAdjustmentType()) {
            case INCREASE -> applyStockDelta(dto.getProductId(), qty, null, null, null, orgId, userId);
            case DECREASE -> applyStockDelta(dto.getProductId(), -qty, null, null, null, orgId, userId);
            case TRANSFER -> { /* handled via StockTransfer */ }
        }

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
            m.put("held", 0f);          // OMS O5a
            out.put(sl.getProductId(), m);
        }
        // Overlay the batch-derived sellable/expired/held split.
        for (Object[] row : stockEntryRepository.sellableExpiredByScope(orgId, userId, today)) {
            Long pid = (Long) row[0];
            float sellable = row[1] == null ? 0f : ((Number) row[1]).floatValue();
            float expired = row[2] == null ? 0f : ((Number) row[2]).floatValue();
            float held = row[3] == null ? 0f : ((Number) row[3]).floatValue();
            java.util.Map<String, Float> m = out.computeIfAbsent(pid, k -> {
                java.util.Map<String, Float> mm = new java.util.HashMap<>();
                mm.put("onHand", 0f);
                return mm;
            });
            m.put("sellable", Math.max(0f, sellable));
            m.put("expired", Math.max(0f, expired));
            // OMS O5a: what a checkout in flight is holding. Sellable was ALREADY net of this, so without the
            // number published there was no way to explain why sellable < on-hand with nothing expired.
            m.put("held", Math.max(0f, held));
        }
        return out;
    }

    /** Single-product {onHand, sellable, expired} for the sell/purchase forms — same split as getLevelDetail,
     *  so the sell screen can show "Sellable: N" (+ an expired badge) and guard the quantity before submit. */
    public java.util.Map<String, Float> getLevelDetailFor(Long productId) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.Map<String, Float> m = new java.util.HashMap<>();
        m.put("onHand", stockLevelRepository.findByProductScoped(productId, orgId, userId)
                .map(sl -> sl.getCurrentStock() == null ? 0f : sl.getCurrentStock()).orElse(0f));
        m.put("sellable", 0f);
        m.put("expired", 0f);
        m.put("held", 0f);              // OMS O5a — see getLevelDetail
        for (Object[] row : stockEntryRepository.sellableExpiredByScope(orgId, userId, today)) {
            if (!productId.equals(row[0])) continue;
            m.put("sellable", Math.max(0f, row[1] == null ? 0f : ((Number) row[1]).floatValue()));
            m.put("expired", Math.max(0f, row[2] == null ? 0f : ((Number) row[2]).floatValue()));
            m.put("held", Math.max(0f, row[3] == null ? 0f : ((Number) row[3]).floatValue()));
            break;
        }
        return m;
    }

    /** Reconcile a purchase EDIT: apply the signed quantity {@code delta} to the purchase's OWN batch
     *  (productId + batchNo) AND the StockLevel, so batch totals, on-hand and sellable stay consistent — no
     *  StockLevel-vs-batch drift. Guarded: a batch can't drop below what's already reserved/sold. Returns new on-hand. */
    @Transactional
    public Float reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust adj) {
        float delta = adj.getDelta() == null ? 0f : adj.getDelta();
        return applyStockDelta(adj.getProductId(), delta, adj.getBatchNo(), adj.getExpiryDate(), adj.getPurchasePrice(),
                CurrentUser.organizationId(), CurrentUser.userId());
    }

    /** Single source of truth for a signed stock correction: apply {@code delta} to a product's BATCHES and its
     *  StockLevel TOGETHER, so on-hand and batch totals (hence sellable) never drift — no matter which side raised
     *  it (a purchase/sale edit, or a product-screen +/− adjust). INCREASE grows the named lot or adds a fresh
     *  batch; DECREASE draws down the named lot first, then newest-first across the product's other lots, guarded
     *  by unreserved availability so sold/held stock is never removed. Returns the new on-hand. */
    private Float applyStockDelta(Long productId, float delta, String batchNo, java.time.LocalDate expiry,
                                  java.math.BigDecimal price, Long orgId, Long userId) {
        StockLevel level = levelFor(productId, orgId, userId);
        float curLevel = level.getCurrentStock() != null ? level.getCurrentStock() : 0f;
        if (delta == 0f) return curLevel;

        // Find the caller's OWN batch when a batchNo is given (so its exact lot is adjusted).
        StockEntry exact = null;
        if (batchNo != null && !batchNo.isBlank()) {
            List<StockEntry> matches = stockEntryRepository.findByProductAndBatchScoped(productId, batchNo, orgId, userId);
            if (!matches.isEmpty()) exact = matches.get(0);
        }

        if (delta > 0f) {
            // INCREASE: grow the exact lot, or (no batchNo / no match) add a fresh batch — always +delta to a batch.
            if (exact != null) {
                exact.setQuantity(nzf(exact.getQuantity()) + delta);
                if (expiry != null) exact.setExpiryDate(expiry);
                if (price != null) exact.setPurchasePrice(price);
                stockEntryRepository.save(exact);
            } else {
                stockEntryRepository.save(StockEntry.builder()
                        .productId(productId).quantity(delta).reservedQuantity(0f)
                        .batchNo(batchNo).expiryDate(expiry)
                        .purchasePrice(price).restockable(true)
                        .organizationId(orgId).userId(userId).build());
            }
        } else {
            // DECREASE: remove |delta| from batches — the exact lot first, then newest-first across the product's
            // other lots (reverse the most-recent receipts). This keeps batch totals in step with on-hand even when
            // the purchase had no batchNo. Guarded by unreserved availability so we never remove sold/held stock.
            float toRemove = -delta;
            if (exact != null) {
                float avail = Math.max(0f, nzf(exact.getQuantity()) - nzf(exact.getReservedQuantity()));
                float take = Math.min(avail, toRemove);
                exact.setQuantity(nzf(exact.getQuantity()) - take);
                stockEntryRepository.save(exact);
                toRemove -= take;
            }
            for (StockEntry e : stockEntryRepository.findByProductNewestFirst(productId, orgId, userId)) {
                if (toRemove <= EPSF) break;
                if (exact != null && e.getId().equals(exact.getId())) continue;
                float avail = nzf(e.getQuantity()) - nzf(e.getReservedQuantity());
                if (avail <= 0f) continue;
                float take = Math.min(avail, toRemove);
                e.setQuantity(nzf(e.getQuantity()) - take);
                stockEntryRepository.save(e);
                toRemove -= take;
            }
            if (toRemove > EPSF) {
                throw new ValidationException("Cannot reduce below stock already reserved/sold");
            }
        }
        float newLevel = Math.max(0f, curLevel + delta);
        level.setCurrentStock(newLevel);
        stockLevelRepository.save(level);
        return newLevel;
    }

    private static final float EPSF = 0.0001f;
    private static float nzf(Float f) { return f == null ? 0f : f; }

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

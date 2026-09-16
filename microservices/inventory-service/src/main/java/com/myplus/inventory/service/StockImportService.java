package com.myplus.inventory.service;

import java.math.BigDecimal;
import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.commerce.contracts.dto.StockImportResult;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bulk opening-stock seed for the item→product migration (slice 33, U2b). For each line: upsert the product's
 * StockLevel (currentStock += quantity, costPrice) and create an opening StockEntry. org/user are params (the
 * controller reads CurrentUser). Not idempotent itself — the caller (business StockMigrationService) gates
 * re-runs via its ItemCatalogMap.stockMigrated flag.
 */
@Service
@RequiredArgsConstructor
public class StockImportService {

    private final StockLevelRepository stockLevelRepository;
    private final StockEntryRepository stockEntryRepository;

    /**
     * ⭐ PERF-9 — returns the resulting ON-HAND, not just a count.
     *
     * <p>The new on-hand is computed three lines below and used to be thrown away, so every caller that
     * wanted the number had to read it back: the Product screen did a POST and then a GET to change one
     * figure on screen. The write now answers with what it wrote — the same thing
     * {@code reconcilePurchase} has always done for a purchase edit.
     *
     * <p>All six bulk callers ignore the return value, so widening it changes nothing for them.
     */
    @Transactional
    public StockImportResult importStock(List<StockImportLine> lines, Long orgId, Long userId) {
        int created = 0;
        Map<Long, BigDecimal> onHand = new LinkedHashMap<>();
        for (StockImportLine l : lines) {
            if (l.getProductId() == null) continue;
            float qty = l.getQuantity() != null ? l.getQuantity() : 0f;

            StockLevel level = stockLevelRepository.findByProductScoped(l.getProductId(), orgId, userId)
                    .orElseGet(() -> StockLevel.builder()
                            .productId(l.getProductId()).currentStock(BigDecimal.ZERO)
                            .organizationId(orgId).userId(userId).build());
            // U0 boundary — see ReservationService: the import contract is still Float, inventory stores exact.
            BigDecimal add = BigDecimal.valueOf(qty);
            level.setCurrentStock((level.getCurrentStock() != null ? level.getCurrentStock() : BigDecimal.ZERO).add(add));
            if (l.getCostPrice() != null) level.setCostPrice(l.getCostPrice());
            stockLevelRepository.save(level);
            // PERF-9: the figure the caller is about to ask for. Recorded per product, so several lines for
            // one product leave the LAST (cumulative) value — which is the on-hand after the whole import.
            onHand.put(l.getProductId(), level.getCurrentStock());

            // #17 P2: carry the exact amount paid onto the BATCH. Without this the field exists on the
            // contract and dies at the seam — the same way a new GL outbox field vanishes unless every hop
            // both populates and reads it. Consumption allocates from this, never from a rounded unit cost.
            // COGS-1: and the quantity that money bought — the fixed divisor it is allocated over. `add` is what was
            // RECEIVED (bonus units included, per PurchaseService), which is exactly what paidTotal paid for.
            StockEntry entry = StockEntry.builder()
                    .paidTotal(l.getPaidTotal())
                    .receivedQuantity(add)
                    .productId(l.getProductId()).quantity(add).reservedQuantity(BigDecimal.ZERO)
                    .batchNo(l.getBatchNo()).expiryDate(l.getExpiryDate()).purchasePrice(l.getPurchasePrice())
                    .organizationId(orgId).userId(userId).build();
            stockEntryRepository.save(entry);
            created++;
        }
        return StockImportResult.builder().created(created).onHand(onHand).build();
    }
}

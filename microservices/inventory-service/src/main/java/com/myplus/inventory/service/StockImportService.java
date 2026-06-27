package com.myplus.inventory.service;

import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Transactional
    public int importStock(List<StockImportLine> lines, Long orgId, Long userId) {
        int created = 0;
        for (StockImportLine l : lines) {
            if (l.getProductId() == null) continue;
            float qty = l.getQuantity() != null ? l.getQuantity() : 0f;

            StockLevel level = stockLevelRepository.findByProductScoped(l.getProductId(), orgId, userId)
                    .orElseGet(() -> StockLevel.builder()
                            .productId(l.getProductId()).currentStock(0f)
                            .organizationId(orgId).userId(userId).build());
            level.setCurrentStock((level.getCurrentStock() != null ? level.getCurrentStock() : 0f) + qty);
            if (l.getCostPrice() != null) level.setCostPrice(l.getCostPrice());
            stockLevelRepository.save(level);

            StockEntry entry = StockEntry.builder()
                    .productId(l.getProductId()).quantity(qty).reservedQuantity(0f)
                    .batchNo(l.getBatchNo()).expiryDate(l.getExpiryDate()).purchasePrice(l.getPurchasePrice())
                    .organizationId(orgId).userId(userId).build();
            stockEntryRepository.save(entry);
            created++;
        }
        return created;
    }
}

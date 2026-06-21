package com.myplus.business_service.service;

import com.myplus.business_service.dto.StockMigrationResult;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.StockRepo;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.StockImportLine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Stock→inventory seed (slice 33, U2b). For each mapped item whose stock hasn't been seeded, reads the local
 * business Stock and sends an opening line to inventory; then marks the map {@code stockMigrated}. Idempotent:
 * a re-run only processes maps still flagged not-migrated. Runs after the item→product migration (U2).
 */
@Service
@RequiredArgsConstructor
public class StockMigrationService {

    private final ItemCatalogMapRepo mapRepo;
    private final StockRepo stockRepo;
    private final InventoryClient inventoryClient;

    @Transactional
    public StockMigrationResult migrateStock(Long orgId, Long userId) {
        List<ItemCatalogMap> maps = mapRepo.findByOrganizationIdAndStockMigratedFalse(orgId);
        List<StockImportLine> lines = new ArrayList<>();

        for (ItemCatalogMap m : maps) {
            stockRepo.findByItemId(m.getItemId()).ifPresent(s -> lines.add(StockImportLine.builder()
                    .productId(m.getProductId())
                    .quantity(s.getStock())
                    .batchNo(s.getBatchNo())
                    .expiryDate(s.getBexpDate())
                    .purchasePrice(s.getBpurchaseRate())
                    .costPrice(s.getBpurchaseRate())
                    .build()));
        }

        int seeded = 0;
        if (!lines.isEmpty()) {
            Integer count = inventoryClient.importStock(lines);
            seeded = count != null ? count : 0;
        }
        // Mark all considered maps migrated (items with no local stock have nothing to seed — done).
        maps.forEach(m -> m.setStockMigrated(true));
        mapRepo.saveAll(maps);

        return new StockMigrationResult(maps.size(), seeded);
    }
}

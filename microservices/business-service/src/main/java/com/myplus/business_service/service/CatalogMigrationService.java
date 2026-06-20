package com.myplus.business_service.service;

import com.myplus.business_service.dto.CatalogMigrationResult;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.ItemRepo;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Item→Product migration (slice 33, U2). Reads the org's business Items, sends the not-yet-migrated ones to
 * catalog-service's bulk import, and records the itemId→productId map. Idempotent / re-runnable: items already
 * in {@link ItemCatalogMap} are skipped. org/user are params (the controller reads the authenticated user).
 */
@Service
@RequiredArgsConstructor
public class CatalogMigrationService {

    private final ItemRepo itemRepo;
    private final ItemCatalogMapRepo mapRepo;
    private final CatalogClient catalogClient;

    @Transactional
    public CatalogMigrationResult migrate(Long orgId, Long userId) {
        List<Item> items = itemRepo.findScoped(orgId, userId);
        Set<Long> alreadyMapped = new HashSet<>(mapRepo.findItemIdsByOrganizationId(orgId));

        List<ProductImportLine> toImport = items.stream()
                .filter(i -> !alreadyMapped.contains(i.getId()))
                .map(this::toLine)
                .toList();

        if (toImport.isEmpty()) {
            return new CatalogMigrationResult(items.size(), 0, alreadyMapped.size());
        }

        List<ProductImportResult> results = catalogClient.importProducts(toImport);
        for (ProductImportResult r : results) {
            mapRepo.save(ItemCatalogMap.builder()
                    .itemId(r.getClientRef()).productId(r.getProductId()).organizationId(orgId)
                    .build());
        }
        return new CatalogMigrationResult(items.size(), results.size(), alreadyMapped.size());
    }

    private ProductImportLine toLine(Item i) {
        return ProductImportLine.builder()
                .clientRef(i.getId())
                .sku(i.getIcode())
                .name(i.getIname())
                .description(i.getIdesc())
                .unit(i.getUnit())
                .manufacturer(i.getCompany() != null ? i.getCompany().getName() : null)
                .categoryName(i.getCategory())
                .build();
    }
}

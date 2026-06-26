package com.myplus.business_service.service;

import com.myplus.business_service.dto.ProductSyncDTO;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.ItemRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product→Item master-sync (slice 53). Projects a catalog Product (the authoritative master) into a bridged
 * business {@code Item} + {@code ItemCatalogMap} so the itemId-based screens (POS/pharmacy picker) surface the one
 * master and the saga can translate itemId→productId. Idempotent on (org, productId): re-syncing updates the Item.
 */
@Service
@RequiredArgsConstructor
public class ProductSyncService {

    private final ItemRepo itemRepo;
    private final ItemCatalogMapRepo mapRepo;

    @Transactional
    public Long syncFromProduct(ProductSyncDTO dto, Long orgId, Long userId) {
        ItemCatalogMap map = mapRepo.findByProductId(dto.getProductId(), orgId).orElse(null);

        Item item = (map != null) ? itemRepo.findById(map.getItemId()).orElseGet(Item::new) : new Item();
        item.setIname(dto.getName());
        item.setIcode(dto.getSku());
        item.setIdesc(dto.getDescription());
        item.setUnit(dto.getUnit());
        item.setCategory(dto.getCategory());
        if (item.getOrganizationId() == null) item.setOrganizationId(orgId);
        if (item.getUserId() == null) item.setUserId(userId);
        item = itemRepo.save(item);

        if (map == null) {
            mapRepo.save(ItemCatalogMap.builder()
                    .itemId(item.getId()).productId(dto.getProductId()).organizationId(orgId)
                    .stockMigrated(true)   // product-native: stock lives in inventory, no local Stock to seed
                    .build());
        }
        return item.getId();
    }
}

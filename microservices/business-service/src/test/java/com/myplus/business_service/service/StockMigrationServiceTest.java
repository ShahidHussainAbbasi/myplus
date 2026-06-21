package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.myplus.business_service.dto.StockMigrationResult;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.StockRepo;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.StockImportLine;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 33, U2b — pure Mockito (always runs, no Docker). Only items with local stock are seeded into
 * inventory; every considered map is flagged stockMigrated.
 */
@ExtendWith(MockitoExtension.class)
class StockMigrationServiceTest {

    @Mock private ItemCatalogMapRepo mapRepo;
    @Mock private StockRepo stockRepo;
    @Mock private InventoryClient inventoryClient;
    @InjectMocks private StockMigrationService service;

    private ItemCatalogMap map(Long itemId, Long productId) {
        return ItemCatalogMap.builder().itemId(itemId).productId(productId).organizationId(1L).build();
    }

    private Stock stock(Long itemId, float qty) {
        Stock s = new Stock();
        s.setItemId(itemId);
        s.setStock(qty);
        s.setBatchNo("B" + itemId);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void seeds_only_items_with_stock_and_flags_all_considered_maps() {
        when(mapRepo.findUnmigratedStock(1L))
                .thenReturn(List.of(map(1L, 100L), map(2L, 200L)));
        when(stockRepo.findByItemId(1L)).thenReturn(Optional.of(stock(1L, 30f)));
        when(stockRepo.findByItemId(2L)).thenReturn(Optional.empty()); // no local stock
        when(inventoryClient.importStock(anyList())).thenReturn(1);

        StockMigrationResult result = service.migrateStock(1L, 9L);

        assertThat(result.getItemsConsidered()).isEqualTo(2);
        assertThat(result.getStockSeeded()).isEqualTo(1);

        // Only the item with stock (product 100) was sent.
        ArgumentCaptor<List<StockImportLine>> sent = ArgumentCaptor.forClass(List.class);
        verify(inventoryClient).importStock(sent.capture());
        assertThat(sent.getValue()).extracting(StockImportLine::getProductId).containsExactly(100L);

        // Both maps flagged migrated and saved.
        ArgumentCaptor<List<ItemCatalogMap>> savedMaps = ArgumentCaptor.forClass(List.class);
        verify(mapRepo).saveAll(savedMaps.capture());
        assertThat(savedMaps.getValue()).allMatch(ItemCatalogMap::isStockMigrated);
    }
}

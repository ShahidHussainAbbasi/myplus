package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.myplus.business_service.dto.CatalogMigrationResult;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.ItemRepo;
import com.myplus.business_service.repository.StockRepo;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 33, U2 — pure Mockito (always runs, no Docker). Proves the migration sends only NOT-yet-mapped items
 * to catalog and persists the returned itemId→productId map.
 */
@ExtendWith(MockitoExtension.class)
class CatalogMigrationServiceTest {

    @Mock private ItemRepo itemRepo;
    @Mock private ItemCatalogMapRepo mapRepo;
    @Mock private CatalogClient catalogClient;
    @Mock private StockRepo stockRepo;
    @InjectMocks private CatalogMigrationService service;

    private Item item(Long id, String icode, String iname) {
        Item i = new Item();
        i.setId(id);
        i.setIcode(icode);
        i.setIname(iname);
        return i;
    }

    @Test
    @SuppressWarnings("unchecked")
    void migrates_only_unmapped_items_and_persists_the_map() {
        when(itemRepo.findScoped(1L, 9L)).thenReturn(List.of(item(1L, "A1", "Aspirin"), item(2L, "B1", "Bandage")));
        when(mapRepo.findMappedItemIds(1L)).thenReturn(List.of(1L)); // item 1 already migrated
        when(catalogClient.importProducts(anyList())).thenReturn(List.of(new ProductImportResult(2L, 200L)));

        CatalogMigrationResult result = service.migrate(1L, 9L);

        assertThat(result.getTotalItems()).isEqualTo(2);
        assertThat(result.getMigrated()).isEqualTo(1);
        assertThat(result.getAlreadyMapped()).isEqualTo(1);

        // Only the unmapped item (id 2) was sent to catalog.
        ArgumentCaptor<List<ProductImportLine>> sent = ArgumentCaptor.forClass(List.class);
        verify(catalogClient).importProducts(sent.capture());
        assertThat(sent.getValue()).extracting(ProductImportLine::getClientRef).containsExactly(2L);

        // The returned mapping was persisted.
        ArgumentCaptor<ItemCatalogMap> saved = ArgumentCaptor.forClass(ItemCatalogMap.class);
        verify(mapRepo).save(saved.capture());
        assertThat(saved.getValue().getItemId()).isEqualTo(2L);
        assertThat(saved.getValue().getProductId()).isEqualTo(200L);
    }

    @Test
    void ensureMapped_returns_existing_productId_without_importing() {   // M3.2 (slice 63)
        when(mapRepo.findProductIdByItemId(7L, 1L)).thenReturn(Optional.of(100L));

        assertThat(service.ensureMapped(7L, 1L, 9L)).isEqualTo(100L);
        verify(catalogClient, never()).importProducts(anyList());
        verify(mapRepo, never()).save(any());
    }

    @Test
    void ensureMapped_imports_and_maps_an_unmapped_item() {   // M3.2 (slice 63)
        when(mapRepo.findProductIdByItemId(7L, 1L)).thenReturn(Optional.empty());
        when(itemRepo.findById(7L)).thenReturn(Optional.of(item(7L, "L7", "Legacy")));
        when(stockRepo.findByItemId(7L)).thenReturn(Optional.empty());
        ProductImportResult r = org.mockito.Mockito.mock(ProductImportResult.class);
        when(r.getClientRef()).thenReturn(7L);
        when(r.getProductId()).thenReturn(200L);
        when(catalogClient.importProducts(anyList())).thenReturn(List.of(r));

        assertThat(service.ensureMapped(7L, 1L, 9L)).isEqualTo(200L);
        verify(mapRepo).save(any(ItemCatalogMap.class));
    }
}

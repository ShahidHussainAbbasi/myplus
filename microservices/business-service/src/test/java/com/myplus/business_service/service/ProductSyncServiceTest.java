package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.myplus.business_service.dto.ProductSyncDTO;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.ItemRepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 53 — Product→Item master-sync. Pure Mockito (always runs, no Docker): first sync creates an Item + a map;
 * a re-sync updates the existing Item and does NOT create a second map (idempotent on org+productId).
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncServiceTest {

    @Mock private ItemRepo itemRepo;
    @Mock private ItemCatalogMapRepo mapRepo;
    @InjectMocks private ProductSyncService service;

    private ProductSyncDTO dto(Long productId, String name) {
        ProductSyncDTO d = new ProductSyncDTO();
        d.setProductId(productId); d.setName(name); d.setSku("SKU" + productId);
        d.setUnit("pcs"); d.setDescription("d"); d.setCategory("General");
        return d;
    }

    @Test
    void first_sync_creates_an_item_and_a_map() {
        when(mapRepo.findByProductId(100L, 1L)).thenReturn(Optional.empty());
        when(itemRepo.save(any(Item.class))).thenAnswer(inv -> { Item i = inv.getArgument(0); i.setId(7L); return i; });

        Long itemId = service.syncFromProduct(dto(100L, "Aspirin"), 1L, 9L);

        assertThat(itemId).isEqualTo(7L);
        ArgumentCaptor<Item> item = ArgumentCaptor.forClass(Item.class);
        verify(itemRepo).save(item.capture());
        assertThat(item.getValue().getIname()).isEqualTo("Aspirin");
        assertThat(item.getValue().getOrganizationId()).isEqualTo(1L);
        assertThat(item.getValue().getUserId()).isEqualTo(9L);

        ArgumentCaptor<ItemCatalogMap> map = ArgumentCaptor.forClass(ItemCatalogMap.class);
        verify(mapRepo).save(map.capture());
        assertThat(map.getValue().getItemId()).isEqualTo(7L);
        assertThat(map.getValue().getProductId()).isEqualTo(100L);
        assertThat(map.getValue().isStockMigrated()).isTrue();   // product-native, stock lives in inventory
    }

    @Test
    void re_sync_updates_the_existing_item_and_adds_no_second_map() {
        Item existing = new Item();
        existing.setId(7L); existing.setOrganizationId(1L); existing.setUserId(9L); existing.setIname("Old");
        when(mapRepo.findByProductId(100L, 1L))
                .thenReturn(Optional.of(ItemCatalogMap.builder().itemId(7L).productId(100L).organizationId(1L).build()));
        when(itemRepo.findById(7L)).thenReturn(Optional.of(existing));
        when(itemRepo.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));

        Long itemId = service.syncFromProduct(dto(100L, "Aspirin 500mg"), 1L, 9L);

        assertThat(itemId).isEqualTo(7L);
        assertThat(existing.getIname()).isEqualTo("Aspirin 500mg");   // updated in place
        verify(mapRepo, never()).save(any());                          // no duplicate map
    }
}

package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.myplus.business_service.config.TradeSagaProperties;
import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.common.security.AuthenticatedUser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 33, D3 — pure Mockito (always runs). A purchase, when the saga is enabled, dual-writes the
 * purchased quantity into inventory (translated to the catalog productId); when disabled it does nothing.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseStockInTest {

    @Mock private TradeSagaProperties tradeSagaProperties;
    @Mock private ItemCatalogMapRepo itemCatalogMapRepo;
    @Mock private InventoryClient inventoryClient;
    @InjectMocks private PurchaseService service;

    private static final AuthenticatedUser USER = new AuthenticatedUser(1L, "buyer@test.com", List.of(), 1L);

    private PurchaseDTO purchase(Long itemId, Float qty) {
        PurchaseDTO dto = new PurchaseDTO();
        dto.setItemId(itemId);
        dto.setQuantity(qty);
        return dto;
    }

    private Stock stock(String batch, String rate) {
        Stock s = new Stock();
        s.setBatchNo(batch);
        s.setBpurchaseRate(new BigDecimal(rate));
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void pushes_purchased_quantity_to_inventory_when_saga_enabled() {
        when(tradeSagaProperties.isEnabled()).thenReturn(true);
        when(itemCatalogMapRepo.findProductIdByItemId(5L, 1L)).thenReturn(Optional.of(50L));

        service.pushPurchaseToInventory(purchase(5L, 10f), stock("B1", "5.00"), USER);

        ArgumentCaptor<List<StockImportLine>> sent = ArgumentCaptor.forClass(List.class);
        verify(inventoryClient).importStock(sent.capture());
        StockImportLine line = sent.getValue().get(0);
        assertThat(line.getProductId()).isEqualTo(50L);
        assertThat(line.getQuantity()).isEqualTo(10f);
        assertThat(line.getBatchNo()).isEqualTo("B1");
        assertThat(line.getCostPrice()).isEqualByComparingTo("5.00");
    }

    @Test
    void does_nothing_when_saga_disabled() {
        when(tradeSagaProperties.isEnabled()).thenReturn(false);

        service.pushPurchaseToInventory(purchase(5L, 10f), stock("B1", "5.00"), USER);

        verify(inventoryClient, never()).importStock(anyList());
    }

    @Test
    void does_nothing_for_unmapped_item() {
        when(tradeSagaProperties.isEnabled()).thenReturn(true);
        when(itemCatalogMapRepo.findProductIdByItemId(99L, 1L)).thenReturn(Optional.empty());

        service.pushPurchaseToInventory(purchase(99L, 10f), stock("B1", "5.00"), USER);

        verify(inventoryClient, never()).importStock(anyList());
    }
}

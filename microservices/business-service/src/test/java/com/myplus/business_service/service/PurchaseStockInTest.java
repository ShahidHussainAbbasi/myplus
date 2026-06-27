package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.config.TradeSagaProperties;
import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.entity.Purchase;
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
 * M3b (slice 75) — pure Mockito. A purchase, when the saga is enabled, dual-writes the purchased quantity into
 * inventory using the self-describing Purchase row (productId + batch/rate snapshot); when disabled, or when the item
 * isn't catalog-mapped (productId null), it does nothing.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseStockInTest {

    @Mock private TradeSagaProperties tradeSagaProperties;
    @Mock private ItemCatalogMapRepo itemCatalogMapRepo;
    @Mock private InventoryClient inventoryClient;
    @Mock private CatalogMigrationService catalogMigrationService;
    @InjectMocks private PurchaseService service;

    private static final AuthenticatedUser USER = new AuthenticatedUser(1L, "buyer@test.com", List.of(), 1L);

    private PurchaseDTO dto(Float qty) {
        PurchaseDTO d = new PurchaseDTO();
        d.setItemId(5L);
        d.setQuantity(qty);
        return d;
    }

    /** A saved, self-describing purchase (M3b): productId mapped + batch/rate snapshot on the row. */
    private Purchase purchase(Long productId, String batch, String rate) {
        Purchase p = new Purchase();
        p.setProductId(productId);
        p.setBatchNo(batch);
        p.setBpurchaseRate(rate == null ? null : new BigDecimal(rate));
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void pushes_purchased_quantity_to_inventory_when_saga_enabled() {
        when(tradeSagaProperties.isEnabled()).thenReturn(true);

        service.pushPurchaseToInventory(purchase(50L, "B1", "5.00"), dto(10f), USER);

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

        service.pushPurchaseToInventory(purchase(50L, "B1", "5.00"), dto(10f), USER);

        verify(inventoryClient, never()).importStock(anyList());
    }

    @Test
    void does_nothing_for_unmapped_item() {
        when(tradeSagaProperties.isEnabled()).thenReturn(true);

        service.pushPurchaseToInventory(purchase(null, "B1", "5.00"), dto(10f), USER);  // productId null = unmapped

        verify(inventoryClient, never()).importStock(anyList());
    }
}

package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.myplus.business_service.config.TradeSagaProperties;
import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.service.pricing.MarkupPolicy;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.commerce.contracts.dto.StockImportResult;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.settings.SettingsService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PR-3b — Per batch: a purchase's sell rate belongs to ITS batch. The product's price is not moved (the cost still is),
 * the batch is booked in with its own price, and the purchase keeps the id of the batch it created.
 */
@ExtendWith(MockitoExtension.class)
class PurchasePerBatchTest {

    @Mock private CatalogClient catalogClient;
    @Mock private SettingsService settingsService;
    @Mock private MarkupPolicy markupPolicy;
    @Mock private InventoryClient inventoryClient;
    @Mock private TradeSagaProperties tradeSagaProperties;
    @Mock private PurchaseRepo purchaseRepo;
    @InjectMocks private PurchaseService service;

    private static final AuthenticatedUser USER = new AuthenticatedUser(1L, "buyer@test.com", List.of(), 1L);

    private static Purchase bill(String sell, String cost) {
        Purchase p = new Purchase();
        p.setProductId(50L);
        p.setPurchaseInvoiceNo("PUR-000200");
        p.setBatchNo("B-1003");
        p.setBsellRate(sell == null ? null : new BigDecimal(sell));
        p.setBpurchaseRate(cost == null ? null : new BigDecimal(cost));
        return p;
    }

    /** getChoice as the real one: lower-cased value, matched against the allowed set. */
    private void mode(String value) {
        when(settingsService.getChoice(eq(PurchaseService.PRICE_MODE_KEY), any(), eq(PurchaseService.PRICE_MODE_LATEST)))
                .thenAnswer(i -> {
                    Set<String> allowed = i.getArgument(1);
                    String norm = value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
                    return norm != null && allowed.contains(norm) ? norm : i.getArgument(2);
                });
    }

    @Test
    @DisplayName("per_batch is a recognised mode (not silently read as latest)")
    void per_batch_is_recognised() {
        mode("PER_BATCH");
        assertThat(service.purchasePriceMode()).isEqualTo("per_batch");
    }

    @Test
    @DisplayName("Per batch: the PRODUCT's price is not sent — only the cost")
    void product_price_untouched() {
        mode("per_batch");
        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");
        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000200"), isNull());
    }

    @Test
    @DisplayName("the batch's price: the bill's S/U rate in Per batch, nothing in Latest")
    void batch_price_from_bill() {
        mode("per_batch");
        assertThat(service.batchSellPrice(bill("250.00", "210.00"))).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("Latest (default): no batch price — the product's price applies, as before")
    void latest_has_no_batch_price() {
        mode(null);
        assertThat(service.batchSellPrice(bill("250.00", "210.00"))).isNull();
    }

    @Test
    @DisplayName("Per batch + markup Auto: the batch gets the RULE's price (no guards — a new batch has no price yet)")
    void batch_price_from_rule() {
        mode("per_batch");
        when(markupPolicy.mode()).thenReturn("auto");
        ProductRef ref = new ProductRef();
        ref.setSellingPrice(new BigDecimal("300.00"));   // a higher product price must not hold the batch back
        when(catalogClient.getProduct(50L)).thenReturn(ref);
        when(markupPolicy.suggest(new BigDecimal("210.00"), null, null, null)).thenReturn(new MarkupPolicy.Suggestion(
                "auto", "markup", "exact", new BigDecimal("15"), "BUSINESS", new BigDecimal("210.00"),
                new BigDecimal("241.50"), new BigDecimal("241.50"), null, null));
        assertThat(service.batchSellPrice(bill("250.00", "210.00"))).isEqualByComparingTo("241.50");
    }

    @Test
    @DisplayName("stock-in: the batch is booked with its own price, and the purchase keeps the batch's id")
    @SuppressWarnings("unchecked")
    void stock_in_carries_price_and_keeps_id() {
        mode("per_batch");
        when(tradeSagaProperties.isEnabled()).thenReturn(true);
        when(inventoryClient.importStock(any())).thenReturn(StockImportResult.builder().created(1).entryIds(List.of(777L)).build());
        Purchase p = bill("250.00", "210.00");
        PurchaseDTO dto = new PurchaseDTO();
        dto.setQuantity(10f);

        service.pushPurchaseToInventory(p, dto, USER);

        ArgumentCaptor<List<StockImportLine>> sent = ArgumentCaptor.forClass(List.class);
        verify(inventoryClient).importStock(sent.capture());
        assertThat(sent.getValue().get(0).getSellPrice()).isEqualByComparingTo("250.00");
        assertThat(p.getStockEntryId()).isEqualTo(777L);
        verify(purchaseRepo).save(p);
    }
}

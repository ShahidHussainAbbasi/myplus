package com.myplus.business_service.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Set;

import com.myplus.business_service.entity.Purchase;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.common.settings.SettingsService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PR-1 — {@code pos.pricing.purchaseMode}: does a purchase move the product's selling price?
 *
 * <p>Both call sites (receive, edit) go through {@code stampRatesOnProduct}; this pins what it sends to catalog.
 * The mode is read through the REAL {@code getChoice} contract — the allowed set must be lower-case, which is the
 * mistake this test exists to catch (an upper-case set never matches, and KEEP silently behaves as LATEST).
 */
@ExtendWith(MockitoExtension.class)
class PurchasePriceModeTest {

    @Mock private CatalogClient catalogClient;
    @Mock private SettingsService settingsService;
    @InjectMocks private PurchaseService service;

    private static Purchase bill(String sell, String cost) {
        Purchase p = new Purchase();
        p.setProductId(50L);
        p.setPurchaseInvoiceNo("PUR-000123");
        p.setBsellRate(sell == null ? null : new BigDecimal(sell));
        p.setBpurchaseRate(cost == null ? null : new BigDecimal(cost));
        return p;
    }

    /** What getChoice really does with a stored value — lower-case it, and match it against the allowed set. */
    private void stored(String value) {
        when(settingsService.getChoice(eq(PurchaseService.PRICE_MODE_KEY), any(), eq(PurchaseService.PRICE_MODE_LATEST)))
                .thenAnswer(i -> {
                    Set<String> allowed = i.getArgument(1);
                    String norm = value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
                    return norm != null && allowed.contains(norm) ? norm : i.getArgument(2);
                });
    }

    @Test
    @DisplayName("LATEST (default, nothing stored) → the sell rate re-prices the product, with the bill's number")
    void latest_reprices() {
        stored(null);

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123");
    }

    @Test
    @DisplayName("KEEP → the price is NOT sent; the cost still is")
    void keep_sends_only_the_cost() {
        stored("keep");

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000123"));
    }

    @Test
    @DisplayName("KEEP stored as the settings screen may save it (\"KEEP\") → still KEEP")
    void keep_any_case() {
        stored("KEEP");

        service.stampRatesOnProduct(bill("250.00", "210.00"), "edit");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000123"));
    }

    @Test
    @DisplayName("KEEP and a bill with no cost → nothing to stamp, catalog is not called")
    void keep_without_cost_calls_nothing() {
        stored("keep");

        service.stampRatesOnProduct(bill("250.00", null), "receive");

        verify(catalogClient, never()).updatePrice(any(), any(), any(), any());
    }

    @Test
    @DisplayName("An unknown stored value → LATEST (today's behaviour), never a silent KEEP")
    void unknown_value_is_latest() {
        stored("per_batch");

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123");
    }

    @Test
    @DisplayName("Settings unreadable → LATEST; the purchase is never blocked by a settings outage")
    void settings_outage_is_latest() {
        when(settingsService.getChoice(any(), any(), any())).thenThrow(new RuntimeException("settings down"));

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123");
    }
}

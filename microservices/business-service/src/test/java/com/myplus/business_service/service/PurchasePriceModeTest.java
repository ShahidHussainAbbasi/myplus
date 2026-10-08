package com.myplus.business_service.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
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
    @Mock private com.myplus.business_service.service.pricing.MarkupPolicy markupPolicy;   // PR-2
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

        verify(catalogClient).updatePrice(50L, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123", null);
    }

    @Test
    @DisplayName("KEEP → the price is NOT sent; the cost still is")
    void keep_sends_only_the_cost() {
        stored("keep");

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000123"), isNull());
    }

    @Test
    @DisplayName("KEEP stored as the settings screen may save it (\"KEEP\") → still KEEP")
    void keep_any_case() {
        stored("KEEP");

        service.stampRatesOnProduct(bill("250.00", "210.00"), "edit");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000123"), isNull());
    }

    @Test
    @DisplayName("KEEP and a bill with no cost → nothing to stamp, catalog is not called")
    void keep_without_cost_calls_nothing() {
        stored("keep");

        service.stampRatesOnProduct(bill("250.00", null), "receive");

        verify(catalogClient, never()).updatePrice(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("An unknown stored value → LATEST (today's behaviour), never a silent KEEP")
    void unknown_value_is_latest() {
        stored("fifo_price");   // PR-3b made per_batch a real mode; this case needs a value that is not one

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123", null);
    }

    @Test
    @DisplayName("Settings unreadable → LATEST; the purchase is never blocked by a settings outage")
    void settings_outage_is_latest() {
        when(settingsService.getChoice(any(), any(), any())).thenThrow(new RuntimeException("settings down"));

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123", null);
    }

    // ── PR-2: the markup rule in Auto ───────────────────────────────────────────────────────────────

    private static com.myplus.business_service.service.pricing.MarkupPolicy.Suggestion rule(String price, String guard) {
        return new com.myplus.business_service.service.pricing.MarkupPolicy.Suggestion("auto", "markup", "exact",
                new BigDecimal("15"), "BUSINESS", new BigDecimal("210.00"), price == null ? null : new BigDecimal(price),
                price == null ? null : new BigDecimal(price), new BigDecimal("200.00"), guard);
    }

    private void auto(com.myplus.business_service.service.pricing.MarkupPolicy.Suggestion s) {
        when(markupPolicy.mode()).thenReturn("auto");
        com.myplus.commerce.contracts.dto.ProductRef ref = new com.myplus.commerce.contracts.dto.ProductRef();
        ref.setSellingPrice(new BigDecimal("200.00"));
        when(catalogClient.getProduct(50L)).thenReturn(ref);
        when(markupPolicy.suggest(new BigDecimal("210.00"), null, null, new BigDecimal("200.00"))).thenReturn(s);
    }

    @Test
    @DisplayName("PR-2 Auto: the RULE's price is sent, not the bill's S/U, and the history is told MARKUP")
    void auto_sets_the_rule_price() {
        stored(null);
        auto(rule("241.50", null));

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("241.50"), new BigDecimal("210.00"), "PUR-000123", "MARKUP");
    }

    @Test
    @DisplayName("PR-2 Auto held back by a guard (never lower / cap) → the price does not move; the cost still stamps")
    void auto_guarded_moves_nothing() {
        stored(null);
        auto(rule("190.00", com.myplus.business_service.service.pricing.MarkupPolicy.GUARD_NEVER_LOWER));

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000123"), isNull());
    }

    @Test
    @DisplayName("PR-2 Auto with no rule (no % set) → the bill's S/U rate, as before")
    void auto_without_rule_is_latest() {
        stored(null);
        auto(rule(null, null));

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123", null);
    }

    @Test
    @DisplayName("PR-2 KEEP wins over Auto: the rule is not even asked, only the cost is sent")
    void keep_wins_over_auto() {
        stored("keep");
        lenient().when(markupPolicy.mode()).thenReturn("auto");

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(markupPolicy, never()).suggest(any(), any(), any(), any());
        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000123"), isNull());
    }

    @Test
    @DisplayName("PR-2 Auto and catalog unreadable → the guards cannot be checked, so the price does not move")
    void auto_catalog_down_moves_nothing() {
        stored(null);
        when(markupPolicy.mode()).thenReturn("auto");
        when(catalogClient.getProduct(50L)).thenThrow(new RuntimeException("catalog down"));

        service.stampRatesOnProduct(bill("250.00", "210.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(new BigDecimal("210.00")), eq("PUR-000123"), isNull());
    }
}

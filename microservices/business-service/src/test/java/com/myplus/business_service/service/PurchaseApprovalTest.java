package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.service.pricing.MarkupPolicy;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.settings.SettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PR-4 — in Approval a purchase never moves the price; the price it would set is proposed. In Auto, a change a guard
 * held back is proposed too. Keep never proposes. A queue hiccup never refuses a bill.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseApprovalTest {

    @Mock private CatalogClient catalogClient;
    @Mock private SettingsService settingsService;
    @Mock private MarkupPolicy markupPolicy;
    @InjectMocks private PurchaseService service;

    private static final BigDecimal COST = new BigDecimal("210.00");

    @BeforeEach
    void latestMode() {
        when(settingsService.getChoice(eq(PurchaseService.PRICE_MODE_KEY), any(), eq(PurchaseService.PRICE_MODE_LATEST)))
                .thenReturn(PurchaseService.PRICE_MODE_LATEST);
        when(catalogClient.getProduct(50L)).thenReturn(ProductRef.builder().id(50L).sellingPrice(new BigDecimal("200")).build());
    }

    private static Purchase bill(String sell) {
        Purchase p = new Purchase();
        p.setProductId(50L);
        p.setPurchaseInvoiceNo("PUR-1");
        p.setBsellRate(sell == null ? null : new BigDecimal(sell));
        p.setBpurchaseRate(COST);
        return p;
    }

    private void rule(String mode, String price, String current, String guard) {
        when(markupPolicy.mode()).thenReturn(mode);
        when(markupPolicy.suggest(any(), any(), any(), any())).thenReturn(new MarkupPolicy.Suggestion(mode, "markup", "exact",
                price == null ? null : new BigDecimal("14.5"), "BUSINESS", COST, null,
                price == null ? null : new BigDecimal(price), new BigDecimal(current), guard));
    }

    @Test
    @DisplayName("⭐ Approval: the price does not move (cost stamped); the rule's price is proposed with its reason")
    void approvalProposesTheRulePrice() {
        rule(MarkupPolicy.APPROVAL, "240.45", "200", null);

        service.stampRatesOnProduct(bill("250.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(COST), eq("PUR-1"), isNull());
        verify(catalogClient).proposePrice(eq(50L), eq(new BigDecimal("240.45")), eq(COST), eq("MARKUP"), eq("APPROVAL"),
                contains("14.5% on cost"), eq("PUR-1"));
    }

    @Test
    @DisplayName("Approval with no rule: the bill's sell rate is proposed (source PURCHASE)")
    void approvalWithoutARuleProposesTheBillRate() {
        rule(MarkupPolicy.APPROVAL, null, "200", null);

        service.stampRatesOnProduct(bill("250.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(COST), eq("PUR-1"), isNull());
        verify(catalogClient).proposePrice(eq(50L), eq(new BigDecimal("250.00")), eq(COST), eq("PURCHASE"), eq("APPROVAL"),
                anyString(), eq("PUR-1"));
    }

    @Test
    @DisplayName("⭐ Approval with the catalog unreadable: the price still does NOT move — never the bill's rate")
    void approvalNeverLeaksTheBillRate() {
        when(markupPolicy.mode()).thenReturn(MarkupPolicy.APPROVAL);
        when(catalogClient.getProduct(50L)).thenThrow(new RuntimeException("catalog down"));

        service.stampRatesOnProduct(bill("250.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(COST), eq("PUR-1"), isNull());
        verify(catalogClient, never()).proposePrice(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("⭐ Auto: a change 'never lower' held back is proposed with the guard as its reason")
    void autoHeldBackIsProposed() {
        rule(MarkupPolicy.AUTO, "240.45", "300", MarkupPolicy.GUARD_NEVER_LOWER);

        service.stampRatesOnProduct(bill("250.00"), "receive");

        verify(catalogClient).updatePrice(eq(50L), isNull(), eq(COST), eq("PUR-1"), isNull());
        verify(catalogClient).proposePrice(eq(50L), eq(new BigDecimal("240.45")), eq(COST), eq("MARKUP"), eq("NEVER_LOWER"),
                anyString(), eq("PUR-1"));
    }

    @Test
    @DisplayName("Auto that applies: the rule sets the price at once, nothing is proposed (unchanged from PR-2)")
    void autoAppliesAsBefore() {
        rule(MarkupPolicy.AUTO, "240.45", "200", null);

        service.stampRatesOnProduct(bill("250.00"), "receive");

        verify(catalogClient).updatePrice(50L, new BigDecimal("240.45"), COST, "PUR-1", "MARKUP");
        verify(catalogClient, never()).proposePrice(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Keep never proposes — 'never changes the price' means never")
    void keepNeverProposes() {
        when(settingsService.getChoice(eq(PurchaseService.PRICE_MODE_KEY), any(), eq(PurchaseService.PRICE_MODE_LATEST)))
                .thenReturn(PurchaseService.PRICE_MODE_KEEP);
        rule(MarkupPolicy.APPROVAL, "240.45", "200", null);

        service.stampRatesOnProduct(bill("250.00"), "receive");

        verify(catalogClient, never()).proposePrice(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a proposal that fails never refuses the bill")
    void aQueueHiccupIsBestEffort() {
        rule(MarkupPolicy.APPROVAL, "240.45", "200", null);
        doThrow(new RuntimeException("catalog 503")).when(catalogClient)
                .proposePrice(any(), any(), any(), any(), any(), any(), any());

        assertThatCode(() -> service.stampRatesOnProduct(bill("250.00"), "receive")).doesNotThrowAnyException();
    }
}

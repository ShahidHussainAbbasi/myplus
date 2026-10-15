package com.myplus.business_service.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.service.pricing.MarkupPolicy;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.common.settings.SettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TP-4 — an edited purchase re-prices the product (selling price + last purchase/sale rates) only when it is the
 * product's LATEST bill. Live defect: an edit to an older Desora bill moved the price 309.15 → 297.70 over a newer bill.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseEditLatestBillTest {

    @Mock private CatalogClient catalogClient;
    @Mock private SettingsService settingsService;
    @Mock private MarkupPolicy markupPolicy;
    @Mock private PurchaseRepo purchaseRepo;
    @InjectMocks private PurchaseService service;

    private static final LocalDateTime DATED = LocalDateTime.of(2026, 9, 20, 10, 15);

    @BeforeEach
    void latestMode() {
        lenient().when(settingsService.getChoice(eq(PurchaseService.PRICE_MODE_KEY), any(), eq(PurchaseService.PRICE_MODE_LATEST)))
                .thenReturn(PurchaseService.PRICE_MODE_LATEST);
    }

    private static Purchase bill() {
        Purchase p = new Purchase();
        p.setPurchaseId(3106L);
        p.setProductId(50L);
        p.setOrganizationId(7L);
        p.setUserId(1L);
        p.setDated(DATED);
        p.setPurchaseInvoiceNo("PUR-003106");
        p.setBsellRate(new BigDecimal("297.70"));
        p.setBpurchaseRate(new BigDecimal("250.00"));
        return p;
    }

    @Test
    @DisplayName("an OLDER bill edited: the product's price and last rates are left alone")
    void older_bill_does_not_reprice() {
        when(purchaseRepo.countNewerPurchases(50L, 3106L, DATED, 7L, 1L)).thenReturn(1L);
        service.stampRatesOnEdit(bill());
        verifyNoInteractions(catalogClient);
    }

    @Test
    @DisplayName("the LATEST bill edited: re-prices and re-stamps both rates, as a receipt does")
    void latest_bill_reprices() {
        when(purchaseRepo.countNewerPurchases(50L, 3106L, DATED, 7L, 1L)).thenReturn(0L);
        service.stampRatesOnEdit(bill());
        verify(catalogClient).updatePrice(eq(50L), eq(new BigDecimal("297.70")), eq(new BigDecimal("250.00")),
                eq("PUR-003106"), any());
    }

    @Test
    @DisplayName("the question is asked of THIS tenant and THIS product, by the bill's own date")
    void asks_with_the_bills_own_facts() {
        when(purchaseRepo.countNewerPurchases(anyLong(), anyLong(), any(), any(), any())).thenReturn(2L);
        service.stampRatesOnEdit(bill());
        verify(purchaseRepo).countNewerPurchases(50L, 3106L, DATED, 7L, 1L);
        verify(catalogClient, never()).updatePrice(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("no product on the bill: nothing is asked, nothing is stamped")
    void no_product_no_stamp() {
        Purchase p = bill();
        p.setProductId(null);
        service.stampRatesOnEdit(p);
        verifyNoInteractions(purchaseRepo, catalogClient);
    }
}

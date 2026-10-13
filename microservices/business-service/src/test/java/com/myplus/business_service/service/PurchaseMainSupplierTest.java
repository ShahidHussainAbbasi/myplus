package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.myplus.business_service.entity.Purchase;
import com.myplus.commerce.contracts.client.CatalogClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** SSR-0c — a received bill names the product's main supplier in catalog (catalog keeps only the first). */
@ExtendWith(MockitoExtension.class)
class PurchaseMainSupplierTest {

    @Mock private CatalogClient catalogClient;
    @InjectMocks private PurchaseService service;

    private static Purchase bill(Long vender) {
        Purchase p = new Purchase();
        p.setProductId(50L);
        p.setVenderId(vender);
        return p;
    }

    @Test
    @DisplayName("a bill from a supplier stamps that supplier on the product")
    void stamps() {
        service.stampMainSupplier(bill(7L));
        verify(catalogClient).stampMainSupplier(50L, 7L);
    }

    @Test
    @DisplayName("a cash purchase (no supplier) names nobody")
    void cash_purchase() {
        service.stampMainSupplier(bill(null));
        verifyNoInteractions(catalogClient);
    }

    @Test
    @DisplayName("catalog unreachable: the purchase is not refused")
    void best_effort() {
        when(catalogClient.stampMainSupplier(any(), any())).thenThrow(new RuntimeException("catalog down"));
        assertThatCode(() -> service.stampMainSupplier(bill(7L))).doesNotThrowAnyException();
    }
}

package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import com.myplus.business_service.entity.Purchase;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.settings.Capability;
import com.myplus.common.settings.CapabilityService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EXP-REQ (§12.18) — where the business tracks expiry, a purchase needs an expiry date unless the product is marked
 * "No expiry". Elsewhere it may be blank. Fails CLOSED when the product cannot be read.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseExpiryRequiredTest {

    @Mock private CatalogClient catalogClient;
    @Mock private CapabilityService capabilityService;
    @InjectMocks private PurchaseService service;

    private static Purchase bill(LocalDate expiry) {
        Purchase p = new Purchase();
        p.setProductId(50L);
        p.setBexpDate(expiry);
        return p;
    }

    private static ProductRef product(Boolean noExpiry) {
        ProductRef r = new ProductRef();
        r.setName("Panadol");
        r.setNoExpiry(noExpiry);
        return r;
    }

    @Test
    @DisplayName("business does not track expiry (POS, Mobile Shop): blank is fine, the product is not even read")
    void untracked_business_allows_blank() {
        when(capabilityService.isEnabled(Capability.EXPIRY_TRACKING)).thenReturn(false);
        assertThatCode(() -> service.requireExpiryUnlessExempt(bill(null))).doesNotThrowAnyException();
        verifyNoInteractions(catalogClient);
    }

    @Test
    @DisplayName("an expiry was entered: nothing to check")
    void dated_bill_passes() {
        assertThatCode(() -> service.requireExpiryUnlessExempt(bill(LocalDate.of(2027, 3, 31))))
                .doesNotThrowAnyException();
        verifyNoInteractions(catalogClient, capabilityService);
    }

    @Test
    @DisplayName("pharmacy, ordinary product, blank expiry: refused, naming the product and the way out")
    void tracked_business_refuses_blank() {
        when(capabilityService.isEnabled(Capability.EXPIRY_TRACKING)).thenReturn(true);
        when(catalogClient.getProduct(50L)).thenReturn(product(false));
        assertThatThrownBy(() -> service.requireExpiryUnlessExempt(bill(null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Panadol").hasMessageContaining("No expiry");
    }

    @Test
    @DisplayName("an older catalog that sends no flag (null) is NOT an exemption")
    void null_flag_is_not_exempt() {
        when(capabilityService.isEnabled(Capability.EXPIRY_TRACKING)).thenReturn(true);
        when(catalogClient.getProduct(50L)).thenReturn(product(null));
        assertThatThrownBy(() -> service.requireExpiryUnlessExempt(bill(null))).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("pharmacy, product marked No expiry: blank is fine")
    void exempt_product_allows_blank() {
        when(capabilityService.isEnabled(Capability.EXPIRY_TRACKING)).thenReturn(true);
        when(catalogClient.getProduct(50L)).thenReturn(product(true));
        assertThatCode(() -> service.requireExpiryUnlessExempt(bill(null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("catalog unreadable and expiry blank: refused (fails closed)")
    void outage_fails_closed() {
        when(capabilityService.isEnabled(Capability.EXPIRY_TRACKING)).thenReturn(true);
        when(catalogClient.getProduct(50L)).thenThrow(new RuntimeException("catalog down"));
        assertThatThrownBy(() -> service.requireExpiryUnlessExempt(bill(null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("expiry date");
    }
}

package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.StockSourceType;
import com.myplus.marketplace.multiseller.dto.SellerDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

/** MKT-4a — the operator marks a supplier: who may be one, what happens to its offers, and the switch it turns on. */
@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    static final long SHOP = 7L, WAREHOUSE = 23L;

    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceOfferRepository offers;
    @Mock MarketplaceSettingsService settings;
    @Mock OfferProjectionService projection;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;
    SupplierService service;

    MarketplaceSellerAccount account;
    final List<MarketplaceOffer> shopOffers = new ArrayList<>();

    @BeforeEach
    void wire() {
        service = new SupplierService(accounts, offers, settings, projection, access, audit);
        account = new MarketplaceSellerAccount();
        account.setOrganizationId(SHOP);
        account.setDisplayName("Phone Supplier");
        account.setStatus("APPROVED");
        account.setVersion(2);
        lenient().when(accounts.findByOrganizationId(SHOP)).thenReturn(Optional.of(account));
        lenient().when(accounts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(accounts.existsBySupplierTrue()).thenAnswer(i -> Boolean.TRUE.equals(account.getSupplier()));
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.of(WAREHOUSE));
        lenient().when(offers.findByOrganizationId(SHOP)).thenReturn(shopOffers);
        shopOffers.add(offer("MERCHANT"));
        shopOffers.add(offer("CONSIGNMENT"));
    }

    static MarketplaceOffer offer(String source) {
        MarketplaceOffer o = new MarketplaceOffer();
        o.setOrganizationId(SHOP);
        o.setStockSourceType(source);
        return o;
    }

    @Test
    @DisplayName("[MKT-R4.3] marked: its merchant offers become SUPPLIER stock, are re-published, and supplier stock is switched on")
    void mark() {
        SellerDTOs.Account a = service.set(SHOP, new SellerDTOs.SupplierRequest(true, false, 2));
        assertThat(a.supplier()).isTrue();
        assertThat(a.supplierStockReliable()).isFalse();
        assertThat(shopOffers.get(0).getStockSourceType()).isEqualTo(StockSourceType.SUPPLIER.name());
        assertThat(shopOffers.get(1).getStockSourceType()).as("not the mark's to change").isEqualTo("CONSIGNMENT");
        verify(projection).publishSeller(SHOP);
        verify(settings).saveSupplierStock(true);
        verify(audit).event(eq("MKT_SUPPLIER_SET"), anyString(), anyString(), eq(SHOP), any(), eq("seller"), eq("supplier"), any(), any());

        service.set(SHOP, new SellerDTOs.SupplierRequest(false, true, null));
        assertThat(shopOffers.get(0).getStockSourceType()).as("unmarked: back to merchant stock").isEqualTo("MERCHANT");
        assertThat(account.getSupplierStockReliable()).as("reliable means nothing without the mark").isFalse();
        verify(settings).saveSupplierStock(false);
    }

    @Test
    @DisplayName("[MKT-R4.4] reliable stock counts are the operator's call, saved with the mark")
    void reliable() {
        SellerDTOs.Account a = service.set(SHOP, new SellerDTOs.SupplierRequest(true, true, null));
        assertThat(a.supplierStockReliable()).isTrue();
        verify(projection).publishSeller(SHOP);
    }

    @Test
    @DisplayName("[MKT-R4.3] refused in a sentence: not approved, the warehouse, no answer; only the operator may mark")
    void refused() {
        account.setStatus("SUSPENDED");
        assertThatThrownBy(() -> service.set(SHOP, new SellerDTOs.SupplierRequest(true, false, null)))
                .isInstanceOf(ValidationException.class).hasMessage("Only an approved seller can be a supplier.");
        account.setStatus("APPROVED");
        account.setOrganizationId(WAREHOUSE);
        when(accounts.findByOrganizationId(WAREHOUSE)).thenReturn(Optional.of(account));
        assertThatThrownBy(() -> service.set(WAREHOUSE, new SellerDTOs.SupplierRequest(true, false, null)))
                .hasMessage("The MaxTheService warehouse sells MaxTheService's own stock, not a supplier's.");
        assertThatThrownBy(() -> service.set(SHOP, new SellerDTOs.SupplierRequest(null, null, null)))
                .hasMessage("Say whether this seller is a supplier.");
        org.mockito.Mockito.doThrow(new AccessDeniedException("operator only")).when(access).assertOperator();
        assertThatThrownBy(() -> service.set(SHOP, new SellerDTOs.SupplierRequest(true, false, null)))
                .isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).saveSupplierStock(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("[MKT-R4.3] supplier stock is sellable once a supplier is marked, as platform stock is once the warehouse is named")
    void sourceSwitch() {
        assertThat(StockSourceType.SUPPLIER.enabledIn(1, false, false)).isFalse();
        assertThat(StockSourceType.SUPPLIER.enabledIn(1, false, true)).isTrue();
        assertThat(StockSourceType.SUPPLIER.enabledIn(1, true, false)).as("the warehouse is not a supplier").isFalse();
        assertThat(StockSourceType.CONSIGNMENT.enabledIn(1, true, true)).isFalse();
    }
}

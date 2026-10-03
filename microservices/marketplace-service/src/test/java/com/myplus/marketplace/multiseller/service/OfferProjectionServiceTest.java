package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePolicyRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

/** MKT-1c — the published projection: when an offer is LIVE, what it carries, and how stock is confirmed. */
@ExtendWith(MockitoExtension.class)
class OfferProjectionServiceTest {

    @Mock MarketplaceOfferProjectionRepository projections;
    @Mock MarketplaceOfferRepository offers;
    @Mock MarketplaceProductRepository products;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplacePolicyRepository policies;
    @Mock InventoryClient inventory;
    @Mock ApplicationEventPublisher events;
    @Mock PlatformTransactionManager txManager;
    @InjectMocks OfferProjectionService service;

    final Map<Long, MarketplaceOfferProjection> rows = new HashMap<>();
    MarketplaceOffer offer;
    MarketplaceProduct product;
    MarketplaceSellerAccount seller;

    @BeforeEach
    void wire() {
        offer = new MarketplaceOffer();
        offer.setId(5L);
        offer.setOrganizationId(7L);
        offer.setSellerOrganizationId(7L);
        offer.setMktProductId(100L);
        offer.setSourceProductId(7100L);
        offer.setStockSourceType("MERCHANT");
        offer.setMarketplacePrice(new BigDecimal("52000.00"));
        offer.setDeliveryAreas("Karachi");
        offer.setPromiseHours(4);
        offer.setWarrantyPolicyId(1L);
        offer.setReturnPolicyId(2L);
        offer.setApprovalStatus("APPROVED");
        offer.setPaused(false);
        product = new MarketplaceProduct();
        product.setId(100L);
        product.setApprovalStatus("APPROVED");
        product.setRegulatedStatus("NONE");
        seller = new MarketplaceSellerAccount();
        seller.setOrganizationId(7L);
        seller.setDisplayName("Shahzad Mobile Shop");
        seller.setStatus("APPROVED");
        MarketplacePolicy w = new MarketplacePolicy();
        w.setWarrantyProvider("Authorised distributor");
        w.setWarrantyMonths(12);
        w.setWarrantyStarts("DELIVERY");
        MarketplacePolicy r = new MarketplacePolicy();
        r.setReturnDays(7);
        lenient().when(projections.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<Long>getArgument(0))));
        lenient().when(projections.save(any())).thenAnswer(i -> {
            MarketplaceOfferProjection p = i.getArgument(0);
            rows.put(p.getOfferId(), p);
            return p;
        });
        lenient().when(products.findById(100L)).thenAnswer(i -> Optional.of(product));
        lenient().when(accounts.findByOrganizationId(7L)).thenAnswer(i -> Optional.of(seller));
        lenient().when(policies.findById(1L)).thenReturn(Optional.of(w));
        lenient().when(policies.findById(2L)).thenReturn(Optional.of(r));
        lenient().when(offers.findById(5L)).thenAnswer(i -> Optional.of(offer));
    }

    @Test
    @DisplayName("[MKT-R7.6] [MKT-R18.2] LIVE only when offer approved and not paused, seller approved, product approved and not regulated")
    void liveRule() {
        assertThat(service.publish(offer).getStatus()).isEqualTo("LIVE");
        offer.setPaused(true);
        assertThat(service.publish(offer).getStatus()).as("paused").isEqualTo("HIDDEN");
        offer.setPaused(false);
        seller.setStatus("SUSPENDED");
        assertThat(service.publish(offer).getStatus()).as("seller suspended").isEqualTo("HIDDEN");
        seller.setStatus("APPROVED");
        offer.setApprovalStatus("SUSPENDED");
        assertThat(service.publish(offer).getStatus()).as("offer suspended").isEqualTo("HIDDEN");
        offer.setApprovalStatus("APPROVED");
        product.setRegulatedStatus("PRESCRIPTION");
        assertThat(service.publish(offer).getStatus()).as("regulated in Phase 1").isEqualTo("HIDDEN");
    }

    @Test
    @DisplayName("[MKT-R14.1] [MKT-R14.2] [MKT-R5.4] the published row carries seller name, price, promise, warranty provider and returns")
    void carriesDisplayFields() {
        MarketplaceOfferProjection p = service.publish(offer);
        assertThat(p.getSellerDisplayName()).isEqualTo("Shahzad Mobile Shop");
        assertThat(p.getPrice()).isEqualByComparingTo("52000");
        assertThat(p.getWarrantyProvider()).isEqualTo("Authorised distributor");
        assertThat(p.getWarrantyMonths()).isEqualTo(12);
        assertThat(p.getWarrantyStarts()).isEqualTo("DELIVERY");
        assertThat(p.getReturnDays()).isEqualTo(7);
        assertThat(p.getLastSyncAt()).as("new LIVE row is unconfirmed until inventory answers").isNull();
    }

    @Test
    @DisplayName("[MKT-R9.2] [MKT-R9.3] the projection has NO column a cost, margin, purchase rate or stock history could leak from")
    void noForbiddenFields() {
        String fields = Arrays.stream(MarketplaceOfferProjection.class.getDeclaredFields()).map(Field::getName)
                .reduce("", (a, b) -> a + "," + b).toLowerCase();
        for (String forbidden : new String[] {"cost", "margin", "purchase", "supplier", "movement", "history"})
            assertThat(fields).as(forbidden).doesNotContain(forbidden);
    }

    @Test
    @DisplayName("[MKT-R18.2] [MKT-R10.3] stock is confirmed as SELLABLE (not on-hand), stamped with when it was confirmed")
    void syncStock() {
        service.publish(offer);
        when(inventory.getSellableDetail(7100L)).thenReturn(Map.of("onHand", 10f, "sellable", 3f, "held", 7f));
        service.syncStock(5L);
        assertThat(rows.get(5L).getAvailableQty()).isEqualByComparingTo("3");
        assertThat(rows.get(5L).getLastSyncAt()).isNotNull();
    }

    @Test
    @DisplayName("[MKT-R18.5] no answer from inventory is not 'zero' and not 'fine': the row stays unconfirmed (stale)")
    void noAnswerStaysUnconfirmed() {
        service.publish(offer);
        when(inventory.getSellableDetail(7100L)).thenReturn(Map.of());
        service.syncStock(5L);
        assertThat(rows.get(5L).getLastSyncAt()).isNull();
        assertThat(rows.get(5L).getAvailableQty()).isNull();
        when(inventory.getSellableDetail(7100L)).thenThrow(new RuntimeException("inventory down"));
        service.onOfferChanged(new OfferChanged(5L));   // best effort: logged, not thrown
        assertThat(rows.get(5L).getLastSyncAt()).isNull();
    }

    @Test
    @DisplayName("[MKT-R18.1] a HIDDEN offer is never sent to inventory; a LIVE one asks for a sync after commit")
    void onlyLiveSyncs() {
        offer.setPaused(true);
        service.publish(offer);
        service.syncStock(5L);
        verify(inventory, never()).getSellableDetail(anyLong());
        verify(events, never()).publishEvent(any(OfferChanged.class));
        offer.setPaused(false);
        service.publish(offer);
        verify(events).publishEvent(new OfferChanged(5L));
    }
}

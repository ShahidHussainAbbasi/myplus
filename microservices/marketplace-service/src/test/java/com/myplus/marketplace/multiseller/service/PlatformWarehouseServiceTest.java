package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.SellerDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

/** MKT-3a — naming the MaxTheService warehouse: who can be it, and when it can change. */
@ExtendWith(MockitoExtension.class)
class PlatformWarehouseServiceTest {

    static final long SHOP = 7L, EMPTY = 9L, PENDING = 11L;

    @Mock MarketplaceSettingsService settings;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceOfferRepository offers;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;
    PlatformWarehouseService service;

    Long warehouse;
    final Map<Long, List<MarketplaceOffer>> offersOf = new HashMap<>();

    @BeforeEach
    void wire() {
        service = new PlatformWarehouseService(settings, accounts, offers, access, audit);
        lenient().when(settings.warehouseOrg()).thenAnswer(i -> Optional.ofNullable(warehouse));
        lenient().doAnswer(i -> { warehouse = i.getArgument(0); return null; }).when(settings).saveWarehouseOrg(any());
        lenient().when(accounts.findByOrganizationId(anyLong())).thenAnswer(i -> Optional.ofNullable(account(i.getArgument(0))));
        lenient().when(accounts.findByStatusOrderByAppliedAtAsc(anyString(), any())).thenAnswer(i -> new PageImpl<>(
                List.of(account(SHOP), account(EMPTY))));
        lenient().when(offers.findByOrganizationId(anyLong())).thenAnswer(i -> offersOf.getOrDefault(i.<Long>getArgument(0), List.of()));
        offersOf.put(SHOP, new ArrayList<>(List.of(offer("APPROVED"))));
    }

    static MarketplaceSellerAccount account(long org) {
        if (org != SHOP && org != EMPTY && org != PENDING) return null;
        MarketplaceSellerAccount a = new MarketplaceSellerAccount();
        a.setOrganizationId(org);
        a.setDisplayName(org == SHOP ? "Shahzad Mobile Shop" : org == EMPTY ? "Central warehouse" : "New shop");
        a.setStatus(org == PENDING ? "PENDING_APPROVAL" : "APPROVED");
        return a;
    }

    static MarketplaceOffer offer(String approval) {
        MarketplaceOffer o = new MarketplaceOffer();
        o.setApprovalStatus(approval);
        return o;
    }

    @Test
    @DisplayName("[MKT-R4.2] [MKT-R20.4] an approved seller with no offers is named; the list offers only such sellers")
    void name() {
        assertThat(service.view().candidates()).extracting(SellerDTOs.Account::organizationId).containsExactly(EMPTY);
        SellerDTOs.Warehouse w = service.set(new SellerDTOs.WarehouseRequest(EMPTY));
        assertThat(w.organizationId()).isEqualTo(EMPTY);
        assertThat(w.organizationName()).isEqualTo("Central warehouse");
        verify(audit).event(org.mockito.ArgumentMatchers.eq("MKT_WAREHOUSE_SET"), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[MKT-R4.2] refused in a sentence: a shop with its own offers, an account not approved, no account")
    void refused() {
        assertThatThrownBy(() -> service.set(new SellerDTOs.WarehouseRequest(SHOP))).isInstanceOf(ValidationException.class)
                .hasMessage("This organisation already has offers of its own. Choose one with none: its offers would become MaxTheService's.");
        assertThatThrownBy(() -> service.set(new SellerDTOs.WarehouseRequest(PENDING))).hasMessage("Choose an approved seller account.");
        assertThatThrownBy(() -> service.set(new SellerDTOs.WarehouseRequest(404L))).hasMessage("Choose an approved seller account.");
        verify(settings, never()).saveWarehouseOrg(any());
    }

    @Test
    @DisplayName("[MKT-R4.2] the warehouse cannot be changed or removed while it has offers live or waiting; suspended ones do not count")
    void changeOnlyWhenEmpty() {
        warehouse = EMPTY;
        offersOf.put(EMPTY, new ArrayList<>(List.of(offer("PENDING_REVIEW"))));
        assertThatThrownBy(() -> service.set(new SellerDTOs.WarehouseRequest(null)))
                .hasMessage("The warehouse has offers live or waiting for approval. Suspend them before removing the warehouse.");
        // the operator's choice is judged first: a shop with offers is refused for that, whatever the warehouse holds
        assertThatThrownBy(() -> service.set(new SellerDTOs.WarehouseRequest(SHOP)))
                .hasMessage("This organisation already has offers of its own. Choose one with none: its offers would become MaxTheService's.");
        offersOf.put(EMPTY, new ArrayList<>(List.of(offer("SUSPENDED"))));
        assertThat(service.set(new SellerDTOs.WarehouseRequest(null)).organizationId()).isNull();
    }

    @Test
    @DisplayName("[MKT-R22.1] a tenant can neither read nor name the warehouse")
    void operatorOnly() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.view()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.set(new SellerDTOs.WarehouseRequest(EMPTY))).isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).saveWarehouseOrg(any());
    }

    @Test
    @DisplayName("[MKT-R4.2] [MKT-R22.1] no shop may call itself MaxTheService, however it is spelt, nor put it in its name")
    void platformName() {
        for (String n : new String[] {"MaxTheService", "max the service", "Max-The-Service", " MAXTHESERVICE ", "MaxTheService Official"})
            assertThat(PlatformWarehouseService.isPlatformName(n)).as(n).isTrue();
        assertThat(PlatformWarehouseService.isPlatformName("Max Mobile Service")).isFalse();
    }
}

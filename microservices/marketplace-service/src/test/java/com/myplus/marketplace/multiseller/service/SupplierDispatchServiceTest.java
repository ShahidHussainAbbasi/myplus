package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.Shipment;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.repository.OrderRepository;
import com.myplus.marketplace.repository.ShipmentRepository;

/** MKT-4b — a supplier's dispatch deadline: stamped at Accept, shown while it runs, settled once it passes. */
@ExtendWith(MockitoExtension.class)
class SupplierDispatchServiceTest {

    static final long SUPPLIER = 24L, STORE = 900L;
    static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 10, 15, 10, 0);
    static final LocalDateTime DUE_BY = ACCEPTED_AT.plusHours(24);

    @Mock MarketplaceSellerOrderRepository sellerOrders;
    @Mock OrderRepository storeOrders;
    @Mock ShipmentRepository shipments;
    @Mock MarketplaceAuditService audit;
    @Mock PlatformTransactionManager txManager;
    SupplierDispatchService service;

    MarketplaceSellerOrder part;
    Order store;
    final List<Shipment> parcels = new ArrayList<>();

    @BeforeEach
    void wire() {
        service = new SupplierDispatchService(sellerOrders, storeOrders, shipments, audit, txManager);
        part = new MarketplaceSellerOrder();
        part.setId(5L);
        part.setSellerOrganizationId(SUPPLIER);
        part.setAcceptanceStatus("ACCEPTED");
        part.setStoreOrderId(STORE);
        part.setStoreOrderNo("SO-1");
        SupplierDispatchService.stamp(part, List.of(line("SUPPLIER")), ACCEPTED_AT);
        store = new Order();
        store.setId(STORE);
        store.setOrganizationId(SUPPLIER);
        store.setFulfilmentStatus(FulfilmentStatus.NEW);
        lenient().when(storeOrders.findById(STORE)).thenAnswer(i -> Optional.of(store));
        lenient().when(shipments.findByOrderIdOrderByShipmentSeqAsc(STORE)).thenAnswer(i -> parcels);
        lenient().when(sellerOrders.findById(5L)).thenAnswer(i -> Optional.of(part));
    }

    static MarketplaceOrderLine line(String source) {
        MarketplaceOrderLine l = new MarketplaceOrderLine();
        l.setStockSourceType(source);
        return l;
    }

    static Shipment parcel(LocalDateTime at, String status) {
        Shipment s = new Shipment();
        s.setShippedAt(at);
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("[MKT-R20.5] at Accept a supplier's part must leave within 24 hours; a shop's part and the warehouse's get no deadline")
    void stamp() {
        assertThat(part.getDispatchBy()).isEqualTo(DUE_BY);
        assertThat(part.getDispatchStatus()).isEqualTo(SupplierDispatchService.DUE);
        for (String other : new String[] {"MERCHANT", "PLATFORM"}) {
            MarketplaceSellerOrder so = new MarketplaceSellerOrder();
            SupplierDispatchService.stamp(so, List.of(line(other)), ACCEPTED_AT);
            assertThat(so.getDispatchBy()).as(other).isNull();
            assertThat(so.getDispatchStatus()).as(other).isNull();
        }
    }

    @Test
    @DisplayName("[MKT-R20.5] the clock shows the time left until a parcel leaves; past the deadline it reads late; once sent it is gone")
    void clock() {
        SupplierDispatchService.Clock c = service.clock(part, store, ACCEPTED_AT.plusHours(1));
        assertThat(c.secondsToShip()).isEqualTo(23 * 3600L);
        assertThat(c.late()).isFalse();
        SupplierDispatchService.Clock past = service.clock(part, store, DUE_BY.plusMinutes(1));
        assertThat(past.secondsToShip()).isZero();
        assertThat(past.late()).isTrue();
        parcels.add(parcel(ACCEPTED_AT.plusHours(3), "DISPATCHED"));
        assertThat(service.clock(part, store, ACCEPTED_AT.plusHours(4)).secondsToShip()).isNull();
        // a cancelled parcel is not a parcel sent
        parcels.set(0, parcel(ACCEPTED_AT.plusHours(3), "CANCELLED"));
        assertThat(service.clock(part, store, ACCEPTED_AT.plusHours(4)).secondsToShip()).isNotNull();
        // a shop's part never shows one
        MarketplaceSellerOrder shop = new MarketplaceSellerOrder();
        shop.setAcceptanceStatus("ACCEPTED");
        assertThat(service.clock(shop)).isEqualTo(new SupplierDispatchService.Clock(null, false));
    }

    @Test
    @DisplayName("[MKT-R20.5] once the deadline passes: sent by then → ON_TIME, sent after or not at all → LATE, ended first → VOID")
    void verdict() {
        LocalDateTime now = DUE_BY.plusMinutes(1);
        assertThat(service.verdict(part, store, now)).isEqualTo(SupplierDispatchService.LATE);
        parcels.add(parcel(DUE_BY.minusMinutes(5), "DISPATCHED"));
        assertThat(service.verdict(part, store, now)).isEqualTo(SupplierDispatchService.ON_TIME);
        parcels.set(0, parcel(DUE_BY.plusSeconds(30), "DISPATCHED"));
        assertThat(service.verdict(part, store, now)).isEqualTo(SupplierDispatchService.LATE);
        parcels.clear();
        store.setFulfilmentStatus(FulfilmentStatus.CANCELLED);
        assertThat(service.verdict(part, store, now)).isEqualTo(SupplierDispatchService.VOID);
        part.setAcceptanceStatus("CANCELLED");
        assertThat(service.verdict(part, store, now)).isEqualTo(SupplierDispatchService.VOID);
        part.setAcceptanceStatus("ACCEPTED");
        part.setDeliveredAt(DUE_BY.minusHours(1));                        // delivered without a parcel record
        assertThat(service.verdict(part, store, now)).isEqualTo(SupplierDispatchService.ON_TIME);
    }

    @Test
    @DisplayName("[MKT-R20.5] the sweep settles a late part once and audits it; a second sweep finds nothing to do")
    void sweepOnce() {
        part.setDispatchBy(LocalDateTime.now().minusMinutes(1));
        when(sellerOrders.findByDispatchStatusAndDispatchByBeforeOrderByDispatchByAsc(eq("DUE"), any(), any()))
                .thenAnswer(i -> "DUE".equals(part.getDispatchStatus()) ? List.of(part) : List.of());
        assertThat(service.sweep()).isEqualTo(1);
        assertThat(part.getDispatchStatus()).isEqualTo(SupplierDispatchService.LATE);
        assertThat(service.sweep()).isZero();
        verify(audit, times(1)).event(eq("MKT_DISPATCH_LATE"), eq("MKT_SELLER_ORDER"), eq("SO-1"), eq(SUPPLIER), any(),
                eq("DUE"), eq("LATE"), any(), anyString());
    }

    @Test
    @DisplayName("[MKT-R20.5] a part sent on time is settled without an audit entry")
    void sweepOnTime() {
        part.setDispatchBy(LocalDateTime.now().minusMinutes(1));
        parcels.add(parcel(LocalDateTime.now().minusHours(2), "DISPATCHED"));
        when(sellerOrders.findByDispatchStatusAndDispatchByBeforeOrderByDispatchByAsc(eq("DUE"), any(), any())).thenReturn(List.of(part));
        assertThat(service.sweep()).isEqualTo(1);
        assertThat(part.getDispatchStatus()).isEqualTo(SupplierDispatchService.ON_TIME);
        verify(audit, never()).event(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any(), any());
    }
}

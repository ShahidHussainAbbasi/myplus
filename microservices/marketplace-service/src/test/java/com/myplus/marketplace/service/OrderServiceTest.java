package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.commerce.contracts.dto.StockReturnRequest;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * E1 (slice 46) — order fulfilment: record (status NEW), list org-scoped, advance status. Real MySQL; skips without Docker.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OrderServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    private static final Long ORG = 1L, USER = 1L;

    @Autowired private OrderService service;
    @Autowired private OrderRepository repo;
    @MockitoBean private InventoryClient inventoryClient;   // reuse the inventory saga; mocked here (slice 49)

    @BeforeEach
    void clean() {
        repo.deleteAll();
        // Default: stock is available — reserve succeeds, confirm/release are no-ops.
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("resv-1", ReservationStatus.RESERVED, List.of(), null));
        when(inventoryClient.confirm(anyString()))
                .thenReturn(new StockReservationResponse("resv-1", ReservationStatus.CONFIRMED, List.of(), null));
        when(inventoryClient.release(anyString()))
                .thenReturn(new StockReservationResponse("resv-1", ReservationStatus.RELEASED, List.of(), null));
    }

    private OrderDTO.Line line(Long productId, int qty) {
        OrderDTO.Line l = new OrderDTO.Line();
        l.setProductId(productId); l.setQuantity(qty); l.setPrice(new BigDecimal("20.00"));
        return l;
    }

    private OrderDTO sample(String invoice) {
        OrderDTO d = new OrderDTO();
        d.setInvoiceNo(invoice);
        d.setCustomerName("Buyer");
        d.setTotal(new BigDecimal("99.00"));
        return d;
    }

    private OrderDTO storefront(String name, String mode, String token) {
        OrderDTO d = new OrderDTO();
        d.setOrganizationId(ORG);
        d.setCustomerName(name);
        d.setTotal(new BigDecimal("20.00"));
        d.setPaymentMode(mode);
        d.setCardToken(token);
        d.setItems(List.of(line(100L, 1)));   // a cart line so the stock reservation runs
        return d;
    }

    @Test
    void public_card_payment_succeeds_and_marks_the_order_paid() {
        OrderDTO o = service.placePublic(storefront("Card Buyer", "CARD", "ok"));
        assertThat(o.getSource()).isEqualTo("STOREFRONT");
        assertThat(o.getPaymentMode()).isEqualTo("CARD");
        assertThat(o.getPaymentStatus()).isEqualTo("PAID");
        assertThat(o.getPaymentRef()).startsWith("ch_sandbox_");
        assertThat(o.getReservationId()).isEqualTo("resv-1");   // stock reserved
        verify(inventoryClient, times(1)).reserve(any());
        verify(inventoryClient, times(1)).confirm("resv-1");    // and confirmed (decremented)
    }

    @Test
    void public_card_decline_releases_the_hold_and_blocks_the_order() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.placePublic(storefront("Declined", "CARD", "fail")));
        assertThat(repo.findScoped(ORG, USER)).isEmpty();   // no order created
        verify(inventoryClient, times(1)).release("resv-1");  // compensating release ran
        verify(inventoryClient, never()).confirm(anyString());
    }

    @Test
    void public_cod_order_is_pending_and_reserves_stock() {
        OrderDTO o = service.placePublic(storefront("COD Buyer", "COD", null));
        assertThat(o.getPaymentMode()).isEqualTo("COD");
        assertThat(o.getPaymentStatus()).isEqualTo("PENDING");
        verify(inventoryClient, times(1)).reserve(any());
        verify(inventoryClient, times(1)).confirm("resv-1");
    }

    @Test
    void out_of_stock_blocks_the_order_and_never_charges() {
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse(null, ReservationStatus.OUT_OF_STOCK, List.of(), "no stock"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.placePublic(storefront("NoStock", "CARD", "ok")));
        assertThat(repo.findScoped(ORG, USER)).isEmpty();     // no order
        verify(inventoryClient, never()).confirm(anyString()); // never decremented
        verify(inventoryClient, never()).release(anyString()); // nothing was held to release
    }

    @Test
    void cancelling_a_storefront_order_returns_its_stock() {
        OrderDTO o = service.placePublic(storefront("Cancel Me", "COD", null));
        OrderDTO cancelled = service.updateStatus(o.getId(), "CANCELLED", ORG, USER);
        assertThat(cancelled.getFulfilmentStatus()).isEqualTo("CANCELLED");
        verify(inventoryClient, times(1)).returnStock(eq("resv-1"), any(StockReturnRequest.class));
    }

    @Test
    void re_cancelling_does_not_return_stock_twice() {
        OrderDTO o = service.placePublic(storefront("Twice", "COD", null));
        service.updateStatus(o.getId(), "CANCELLED", ORG, USER);
        service.updateStatus(o.getId(), "CANCELLED", ORG, USER);   // idempotent — already cancelled
        verify(inventoryClient, times(1)).returnStock(eq("resv-1"), any(StockReturnRequest.class));
    }

    @Test
    void cancelling_a_pos_order_does_not_touch_inventory() {
        OrderDTO created = service.record(sample("INV-X"), ORG, USER);   // POS order, no reservation
        service.updateStatus(created.getId(), "CANCELLED", ORG, USER);
        verify(inventoryClient, never()).returnStock(anyString(), any(StockReturnRequest.class));
    }

    @Test
    void record_then_advance_status_and_list_scoped() {
        OrderDTO created = service.record(sample("INV-1"), ORG, USER);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getFulfilmentStatus()).isEqualTo("NEW");

        OrderDTO shipped = service.updateStatus(created.getId(), "SHIPPED", ORG, USER);
        assertThat(shipped.getFulfilmentStatus()).isEqualTo("SHIPPED");

        assertThat(service.list(ORG, USER)).hasSize(1);
        assertThat(service.list(999L, 999L)).isEmpty();   // another tenant sees nothing
    }
}

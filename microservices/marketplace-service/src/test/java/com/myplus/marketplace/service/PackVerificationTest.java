package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.ShipmentDTO;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderItem;
import com.myplus.marketplace.entity.Shipment;
import com.myplus.marketplace.repository.OrderRepository;
import com.myplus.marketplace.repository.ShipmentRepository;
import com.myplus.marketplace.support.MockWiring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OMS O7 D3 — packing verification: what a shop that requires scanning refuses.
 *
 * <p>Pure Mockito, so it runs on every {@code mvn test}. The point of the setting is that it cannot be
 * bypassed — {@code ShipmentService} is the only writer, so posting to the endpoint directly is refused
 * exactly as the workbench is. These cases pin that, and pin the two states either side of it: OFF changes
 * nothing at all, and a SCANNED parcel is accepted.
 *
 * <p>The setting was withdrawn on 2026-08-10 because it was enforced but unsatisfiable — no UI could send
 * {@code verified}. It is back with the workbench that can. **C2: both halves are asserted here — the key is
 * read AND the consumer honours it** — because a catalog-only assertion passes for a setting nothing reads,
 * which is precisely how this shipped inert the first time.
 */
@ExtendWith(MockitoExtension.class)
class PackVerificationTest {

    private static final Long ORG = 7L, USER = 3L, ORDER_ID = 100L;

    @Mock private OrderRepository orderRepository;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private NotificationService notificationService;
    @Mock private com.myplus.common.settings.SettingsService settingsService;
    @Mock private DispatchInvoiceService dispatchInvoiceService;
    /** O7 D1c: dispatch releases the order's stock promise. Added to the service without its tests. */
    @Mock private OrderStockHoldService orderStockHoldService;
    @InjectMocks private ShipmentService service;

    private Order order;

    @BeforeEach
    void setUp() {
        // A new constructor dependency must fail HERE, naming itself — not as an NPE down a dispatch path.
        MockWiring.assertFullyWired(service);

        OrderItem widget = OrderItem.builder().id(1L).productId(10L).productName("Widget")
                .quantity(5).quantityShipped(0).quantityBackordered(0).build();
        order = Order.builder().id(ORDER_ID).organizationId(ORG)
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .items(new ArrayList<>(List.of(widget)))
                .build();
        lenient().when(orderRepository.findByIdScoped(ORDER_ID, ORG, USER)).thenReturn(Optional.of(order));
        lenient().when(shipmentRepository.maxShipmentSeqForOrg(ORG)).thenReturn(0L);
        lenient().when(shipmentRepository.save(any(Shipment.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        // Not a field order, so no invoice is raised at dispatch — that path is O7 D1's and is tested there.
        lenient().when(dispatchInvoiceService.invoiceForDispatch(any(), any())).thenReturn(null);
    }

    private ShipmentDTO.Request parcel(int qty, Boolean verified) {
        ShipmentDTO.LineRequest l = new ShipmentDTO.LineRequest();
        l.setOrderItemId(1L);
        l.setQuantity(qty);
        l.setVerified(verified);
        ShipmentDTO.Request r = new ShipmentDTO.Request();
        r.setLines(new ArrayList<>(List.of(l)));
        return r;
    }

    private void scanRequired(boolean on) {
        when(settingsService.getBoolFor(anyLong(), anyString())).thenReturn(on);
    }

    @Test
    @DisplayName("scanRequired ON: a hand-typed parcel is refused, and the message says where to go")
    void typedParcelRefusedWhenScanRequired() {
        scanRequired(true);
        assertThatThrownBy(() -> service.ship(ORDER_ID, parcel(2, false), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("scanned")
                // Naming the way out matters: the packer is standing at a box that will not go.
                .hasMessageContaining("Pack");
    }

    @Test
    @DisplayName("scanRequired ON: a SCANNED parcel goes through")
    void scannedParcelAcceptedWhenScanRequired() {
        scanRequired(true);
        ShipmentDTO out = service.ship(ORDER_ID, parcel(2, true), ORG, USER);
        assertThat(out).isNotNull();
        assertThat(order.getItems().get(0).getQuantityShipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("scanRequired OFF (the default): typing is accepted exactly as before")
    void typedParcelAcceptedWhenScanNotRequired() {
        scanRequired(false);
        assertThatCode(() -> service.ship(ORDER_ID, parcel(2, false), ORG, USER)).doesNotThrowAnyException();
        assertThat(order.getItems().get(0).getQuantityShipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("a settings failure FAILS OPEN — a config hiccup must not stop a shop dispatching")
    void settingsFailureDoesNotBlockDispatch() {
        // C3 applies to SAFETY flags; this is not one. A shop that cannot dispatch is a worse outage than an
        // unverified parcel, so the unreadable case resolves to "off".
        when(settingsService.getBoolFor(anyLong(), anyString())).thenThrow(new IllegalStateException("settings down"));
        assertThatCode(() -> service.ship(ORDER_ID, parcel(2, false), ORG, USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a zero-quantity line is not an unverified line — it is an absent one")
    void zeroQuantityLinesAreIgnored() {
        // The workbench posts every line of the order; one the packer did not put in this parcel arrives at
        // zero. Treating that as "unverified" would refuse a fully-scanned parcel because of a line nobody
        // touched.
        scanRequired(true);
        ShipmentDTO.LineRequest packed = new ShipmentDTO.LineRequest();
        packed.setOrderItemId(1L); packed.setQuantity(2); packed.setVerified(true);
        ShipmentDTO.LineRequest untouched = new ShipmentDTO.LineRequest();
        untouched.setOrderItemId(1L); untouched.setQuantity(0); untouched.setVerified(false);
        ShipmentDTO.Request r = new ShipmentDTO.Request();
        r.setLines(new ArrayList<>(List.of(packed, untouched)));

        assertThatCode(() -> service.ship(ORDER_ID, r, ORG, USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("backordered units are not pickable, however the parcel was composed")
    void backorderedUnitsAreNotPickable() {
        // O5c: those units are neither invoiced nor physically present. Scanning cannot conjure them, so the
        // outstanding arithmetic — not the scan flag — is what refuses this.
        order.getItems().get(0).setQuantityBackordered(3);   // 5 ordered, 3 owed ⇒ only 2 pickable
        scanRequired(false);
        assertThatThrownBy(() -> service.ship(ORDER_ID, parcel(5, true), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only 2");
        assertThat(ShipmentService.outstanding(order.getItems().get(0))).isEqualTo(2);
    }
}

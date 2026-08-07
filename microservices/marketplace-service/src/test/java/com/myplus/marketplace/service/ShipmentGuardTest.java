package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OMS O5b — what a dispatch refuses to do.
 *
 * <p>The dangerous cases are the ones that would let the header claim something no parcel accounts for:
 * shipping more than is outstanding, an empty parcel that still burns a SHP- number, and a dispatch against an
 * order that has already been reversed.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentGuardTest {

    private static final Long ORG = 7L, USER = 3L, ORDER_ID = 100L;

    @Mock private OrderRepository orderRepository;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private NotificationService notificationService;
    @InjectMocks private ShipmentService service;

    private Order order;

    @BeforeEach
    void setUp() {
        OrderItem a = OrderItem.builder().id(1L).productId(10L).productName("Widget")
                .quantity(5).quantityShipped(0).build();
        OrderItem b = OrderItem.builder().id(2L).productId(20L).productName("Gadget")
                .quantity(2).quantityShipped(0).build();
        order = Order.builder().id(ORDER_ID).organizationId(ORG)
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .items(new ArrayList<>(List.of(a, b)))
                .build();
        lenient().when(orderRepository.findByIdScoped(ORDER_ID, ORG, USER)).thenReturn(Optional.of(order));
        lenient().when(shipmentRepository.maxShipmentSeqForOrg(ORG)).thenReturn(44L);
        lenient().when(shipmentRepository.save(any(Shipment.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ShipmentDTO.Request req(Long lineId, Integer qty) {
        ShipmentDTO.LineRequest l = new ShipmentDTO.LineRequest();
        l.setOrderItemId(lineId);
        l.setQuantity(qty);
        ShipmentDTO.Request r = new ShipmentDTO.Request();
        r.setLines(new ArrayList<>(List.of(l)));
        return r;
    }

    // ── the happy path ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a partial dispatch moves the line, derives the header and numbers the parcel")
    void partialDispatch() {
        ShipmentDTO out = service.ship(ORDER_ID, req(1L, 2), ORG, USER);

        assertThat(order.getItems().get(0).getQuantityShipped()).isEqualTo(2);
        assertThat(order.getFulfilmentStatus())
                .as("derived from the lines, not typed by anyone")
                .isEqualTo(FulfilmentStatus.PARTIALLY_SHIPPED);
        assertThat(out.getShipmentNo()).isEqualTo("SHP-000045");
        assertThat(out.getLines()).hasSize(1);
    }

    @Test
    @DisplayName("dispatching the remainder of every line flips the header to SHIPPED")
    void fullDispatchFlipsHeader() {
        service.ship(ORDER_ID, req(1L, 5), ORG, USER);
        assertThat(order.getFulfilmentStatus()).isEqualTo(FulfilmentStatus.PARTIALLY_SHIPPED);   // line b still owed
        service.ship(ORDER_ID, req(2L, 2), ORG, USER);
        assertThat(order.getFulfilmentStatus()).isEqualTo(FulfilmentStatus.SHIPPED);
    }

    @Test
    @DisplayName("two parcels for the same line accumulate")
    void splitAcrossParcels() {
        service.ship(ORDER_ID, req(1L, 3), ORG, USER);
        service.ship(ORDER_ID, req(1L, 2), ORG, USER);
        assertThat(order.getItems().get(0).getQuantityShipped()).isEqualTo(5);
    }

    // ── what it refuses ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shipping more than is outstanding is refused, and says how many are left")
    void cannotOverShip() {
        assertThatThrownBy(() -> service.ship(ORDER_ID, req(1L, 6), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only 5 still to go");
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("the outstanding check accounts for what already went")
    void overShipAcrossParcels() {
        service.ship(ORDER_ID, req(1L, 3), ORG, USER);
        assertThatThrownBy(() -> service.ship(ORDER_ID, req(1L, 3), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only 2 still to go");
    }

    @Test
    @DisplayName("an empty parcel is refused rather than burning a shipment number")
    void cannotShipNothing() {
        // It would advance nothing and record nothing, yet appear on the customer's tracking page as though
        // something had been sent.
        assertThatThrownBy(() -> service.ship(ORDER_ID, new ShipmentDTO.Request(), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Nothing to ship");
        assertThatThrownBy(() -> service.ship(ORDER_ID, req(1L, 0), ORG, USER))
                .isInstanceOf(ValidationException.class);
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a line from another order cannot be smuggled into a dispatch")
    void cannotShipAForeignLine() {
        assertThatThrownBy(() -> service.ship(ORDER_ID, req(999L, 1), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not part of this order");
    }

    @Test
    @DisplayName("a cancelled or returned order cannot be shipped")
    void cannotShipAReversedOrder() {
        for (FulfilmentStatus s : new FulfilmentStatus[] { FulfilmentStatus.CANCELLED, FulfilmentStatus.RETURNED }) {
            order.setFulfilmentStatus(s);
            assertThatThrownBy(() -> service.ship(ORDER_ID, req(1L, 1), ORG, USER))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("cannot be shipped");
        }
    }

    @Test
    @DisplayName("nothing is applied when ANY line in the parcel is invalid")
    void allOrNothingValidation() {
        ShipmentDTO.Request r = req(1L, 2);
        ShipmentDTO.LineRequest bad = new ShipmentDTO.LineRequest();
        bad.setOrderItemId(2L);
        bad.setQuantity(99);                    // only 2 outstanding
        r.getLines().add(bad);

        assertThatThrownBy(() -> service.ship(ORDER_ID, r, ORG, USER)).isInstanceOf(ValidationException.class);
        // A half-applied dispatch would leave quantities no parcel accounts for, and the header would then be
        // derived from a fiction.
        assertThat(order.getItems().get(0).getQuantityShipped()).isZero();
        assertThat(order.getFulfilmentStatus()).isEqualTo(FulfilmentStatus.NEW);
    }

    // ── the projection must not undo a decision ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a late parcel does not drag a DELIVERED order back to PARTIALLY_SHIPPED")
    void projectionNeverUndoesADecision() {
        order.getItems().get(0).setQuantityShipped(5);
        order.getItems().get(1).setQuantityShipped(2);
        order.setFulfilmentStatus(FulfilmentStatus.DELIVERED);

        ShipmentService.applyProjection(order);

        assertThat(order.getFulfilmentStatus())
                .as("DELIVERED is a decision someone made; a projection must not quietly reverse it")
                .isEqualTo(FulfilmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("outstanding never goes negative")
    void outstandingIsFloored() {
        OrderItem it = OrderItem.builder().quantity(2).quantityShipped(5).build();
        assertThat(ShipmentService.outstanding(it)).isZero();
    }

    @Test
    @DisplayName("an unscoped order id is a miss, not another tenant's order")
    void scopedLookup() {
        when(orderRepository.findByIdScoped(anyLong(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ship(555L, req(1L, 1), ORG, USER))
                .isInstanceOf(com.myplus.common.web.exception.ResourceNotFoundException.class);
    }
}

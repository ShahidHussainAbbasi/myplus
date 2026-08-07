package com.myplus.marketplace.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.ShipmentDTO;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderItem;
import com.myplus.marketplace.entity.Shipment;
import com.myplus.marketplace.entity.ShipmentLine;
import com.myplus.marketplace.repository.OrderRepository;
import com.myplus.marketplace.repository.ShipmentRepository;

import lombok.RequiredArgsConstructor;

/**
 * OMS O5b — dispatching part or all of an order.
 *
 * <h3>The shipment is the event; the status is a consequence</h3>
 * Nobody marks an order shipped. A parcel is recorded, line quantities move, and the header status is
 * re-projected from those quantities. That ordering is the point of the slice: a header that can be set
 * independently of its shipments is a header that can lie about them.
 *
 * <h3>No stock moves here</h3>
 * O1 decremented inventory when the sale was recorded. This records what physically left against stock that has
 * already gone from the books — decrementing again on dispatch would silently halve the shop's inventory.
 */
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final NotificationService notificationService;

    /**
     * Dispatch a parcel.
     *
     * @param lines orderItemId → quantity going out now; must be a subset of what is still outstanding
     */
    @Transactional
    public ShipmentDTO ship(Long orderId, ShipmentDTO.Request req, Long orgId, Long userId) {
        Order order = orderRepository.findByIdScoped(orderId, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        FulfilmentStatus current = order.getFulfilmentStatus();
        if (current == FulfilmentStatus.CANCELLED || current == FulfilmentStatus.RETURNED)
            throw new ValidationException("A " + current + " order cannot be shipped.");

        Map<Long, Integer> requested = normalise(req);
        if (requested.isEmpty())
            // An empty parcel would advance nothing and record nothing, but would still consume a SHP- number
            // and appear on the customer's tracking page as though something had been sent.
            throw new ValidationException("Nothing to ship — enter a quantity for at least one line.");

        Map<Long, OrderItem> byId = new LinkedHashMap<>();
        for (OrderItem it : order.getItems()) byId.put(it.getId(), it);

        // Validate EVERY line before changing anything: a half-applied dispatch would leave quantities that no
        // parcel accounts for, and the header would then be derived from a fiction.
        for (Map.Entry<Long, Integer> e : requested.entrySet()) {
            OrderItem item = byId.get(e.getKey());
            if (item == null)
                throw new ValidationException("Line " + e.getKey() + " is not part of this order.");
            int outstanding = outstanding(item);
            if (e.getValue() > outstanding) {
                throw new ValidationException("Cannot ship " + e.getValue() + " of "
                        + (item.getProductName() != null ? item.getProductName() : ("product " + item.getProductId()))
                        + " — only " + outstanding + " still to go.");
            }
        }

        long seq = shipmentRepository.maxShipmentSeqForOrg(orgId) + 1;
        Shipment shipment = Shipment.builder()
                .organizationId(orgId).userId(userId).orderId(order.getId())
                .shipmentSeq(seq)
                .shipmentNo(com.myplus.commerce.domain.InvoiceNumbers.shipment(seq))
                .carrier(trimToNull(req.getCarrier()))
                .trackingNumber(trimToNull(req.getTrackingNumber()))
                .note(trimToNull(req.getNote()))
                .status("DISPATCHED")
                .shippedAt(LocalDateTime.now())
                .lines(new ArrayList<>())
                .build();

        for (Map.Entry<Long, Integer> e : requested.entrySet()) {
            OrderItem item = byId.get(e.getKey());
            item.setQuantityShipped(nz(item.getQuantityShipped()) + e.getValue());
            shipment.addLine(ShipmentLine.builder()
                    .orderItemId(item.getId()).quantity(e.getValue()).build());
        }

        Shipment saved = shipmentRepository.save(shipment);
        applyProjection(order);
        Order savedOrder = orderRepository.save(order);

        notificationService.notify(savedOrder, savedOrder.getFulfilmentStatus().name(),
                "Dispatched " + saved.getShipmentNo()
                        + (saved.getCarrier() != null ? " via " + saved.getCarrier() : "")
                        + (saved.getTrackingNumber() != null ? " (" + saved.getTrackingNumber() + ")" : ""));
        return toDTO(saved);
    }

    /** Every parcel for an order, oldest first. */
    @Transactional(readOnly = true)
    public List<ShipmentDTO> forOrder(Long orderId) {
        List<ShipmentDTO> out = new ArrayList<>();
        for (Shipment s : shipmentRepository.findByOrderIdOrderByShipmentSeqAsc(orderId)) out.add(toDTO(s));
        return out;
    }

    /**
     * Re-derive the header from the lines.
     *
     * <p>Only ever moves the order FORWARD through the shipping progression. A terminal or post-delivery state
     * ({@code DELIVERED}, {@code CANCELLED}, {@code RETURNED}, {@code RETURN_REQUESTED}) is a decision someone
     * made, and a projection must not quietly undo it — an order marked DELIVERED must not fall back to
     * PARTIALLY_SHIPPED because a late parcel was recorded.
     */
    static void applyProjection(Order order) {
        int ordered = 0, shipped = 0, backordered = 0;
        for (OrderItem it : order.getItems()) {
            ordered += nz(it.getQuantity());
            shipped += nz(it.getQuantityShipped());
            backordered += nz(it.getQuantityBackordered());   // O5c: owed, not yet invoiced
        }
        FulfilmentStatus projected = FulfilmentStatus.project(ordered, shipped, backordered);
        if (projected == null) return;                       // nothing shipped or owed — NEW/PACKED is the caller's
        FulfilmentStatus current = order.getFulfilmentStatus();
        if (current != null && !current.isDerived() && current != FulfilmentStatus.NEW
                && current != FulfilmentStatus.PACKED) {
            return;                                          // a decision already moved it past shipping
        }
        order.setFulfilmentStatus(projected);
    }

    /**
     * How much of this line can be dispatched now.
     *
     * <p>O5c: backordered units are NOT dispatchable — they have not been invoiced and do not physically exist
     * yet. Shipping against them would send goods the shop has not billed for and cannot pick.
     */
    static int outstanding(OrderItem item) {
        int invoiced = nz(item.getQuantity()) - nz(item.getQuantityBackordered());
        return Math.max(0, invoiced - nz(item.getQuantityShipped()));
    }

    /** Drop nulls, non-positives and duplicates (summing them), so the guards below see one clean number a line. */
    private static Map<Long, Integer> normalise(ShipmentDTO.Request req) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        if (req == null || req.getLines() == null) return out;
        for (ShipmentDTO.LineRequest l : req.getLines()) {
            if (l == null || l.getOrderItemId() == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
            out.merge(l.getOrderItemId(), l.getQuantity(), Integer::sum);
        }
        return out;
    }

    private static ShipmentDTO toDTO(Shipment s) {
        ShipmentDTO d = new ShipmentDTO();
        d.setId(s.getId());
        d.setShipmentNo(s.getShipmentNo());
        d.setCarrier(s.getCarrier());
        d.setTrackingNumber(s.getTrackingNumber());
        d.setStatus(s.getStatus());
        d.setShippedAt(s.getShippedAt());
        d.setNote(s.getNote());
        List<ShipmentDTO.Line> lines = new ArrayList<>();
        for (ShipmentLine l : s.getLines()) {
            ShipmentDTO.Line dl = new ShipmentDTO.Line();
            dl.setOrderItemId(l.getOrderItemId());
            dl.setQuantity(l.getQuantity());
            lines.add(dl);
        }
        d.setLines(lines);
        return d;
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}

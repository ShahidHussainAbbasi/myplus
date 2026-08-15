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
    /** O7 D3 — the per-org packing policy, re-injected with the workbench that can satisfy it. */
    private final com.myplus.common.settings.SettingsService settingsService;
    /** O7 D1 — `ON_DISPATCH`: a field order is invoiced when its goods actually leave, from what left. */
    private final DispatchInvoiceService dispatchInvoiceService;

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

        // OMS O7 D1 — an UNREVIEWED order must never be dispatched.
        //
        // This endpoint is the one that now raises the invoice (`ON_DISPATCH`), so without this guard a booked
        // order could be shipped AND invoiced without anyone approving it — bypassing the review that the whole
        // pre-sales model exists to enforce, through the back door rather than the front. The lifecycle
        // whitelist does not cover it, because dispatch is not a status move: it is a shipment, and the status
        // follows. That is exactly why the check has to be repeated here.
        //
        // The general lesson this programme has hit three times: adding a capability means re-examining the
        // existing REFUSALS. This guard is one that had nothing to refuse until D1 created the state.
        if (current == FulfilmentStatus.PENDING_APPROVAL || current == FulfilmentStatus.REJECTED)
            throw new ValidationException("This order has not been approved yet — confirm it before dispatching.");

        // OMS O5d's scanRequired refusal, WITHDRAWN 2026-08-10 and RESTORED here by O7 D3.
        //
        // The guard was always right in shape — enforce in the ONLY writer, so posting to the endpoint directly
        // cannot bypass the workbench (O3's server-side-COD reasoning). What was wrong is that it had nothing
        // to enforce AGAINST: the only UI that dispatched never sent `verified`, so switching the setting on
        // refused EVERY dispatch and told the packer to scan into a workbench that did not exist.
        //
        // D3 built that workbench. A scanned line now arrives verified, so a shop that turns this on gets what
        // it asked for, and one that leaves it off is completely unaffected.
        if (scanRequired(orgId) && hasUnverifiedLine(req)) {
            throw new ValidationException("This shop requires items to be scanned when packing. "
                    + "Use Pack on the order to scan each item into the parcel, or turn off \"Require items to "
                    + "be scanned\" in Order settings.");
        }

        Map<Long, Integer> requested = normalise(req);
        if (requested.isEmpty())
            // An empty parcel would advance nothing and record nothing, but would still consume a SHP- number
            // and appear on the customer's tracking page as though something had been sent.
            throw new ValidationException("Nothing to ship — enter a quantity for at least one line.");

        java.util.Map<Long, Boolean> verifiedByLine = new java.util.HashMap<>();
        if (req.getLines() != null)
            for (ShipmentDTO.LineRequest l : req.getLines())
                if (l != null && l.getOrderItemId() != null)
                    verifiedByLine.merge(l.getOrderItemId(), Boolean.TRUE.equals(l.getVerified()), (a, b) -> a && b);

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

        // OMS O7 D1 — `ON_DISPATCH`. A field order carries no invoice until its goods leave, so raise it HERE,
        // from the quantities actually going out, and only once every line above has been validated.
        //
        // Before the writes below, deliberately: if the invoice cannot be raised the parcel must not exist.
        // Letting it through would send stock out of the building with no sale, no AR and no tax record behind
        // it — OMS-1, the defect this programme started with. A no-op for POS and storefront orders, which were
        // invoiced when they were placed.
        String dispatchInvoice = dispatchInvoiceService.invoiceForDispatch(order, requested);
        if (dispatchInvoice != null) {
            order.setInvoiceNo(dispatchInvoice);
            order.setBooksStatus("POSTED");
        }
        // O7 D4: the invoice belongs on the PARCEL as well. `orders.invoice_no` is overwritten by each dispatch,
        // so a part-delivered order could only ever be credited for its LAST shipment — the limitation D1
        // recorded (§8.1c) and this slice owns. An invoice now corresponds one-for-one to a parcel.
        //
        // For a POS or storefront order (invoiced at placement) `dispatchInvoice` is null, so the parcel carries
        // the ORDER's invoice — which is the right one, because there is only ever one.
        String parcelInvoice = dispatchInvoice != null ? dispatchInvoice : order.getInvoiceNo();

        long seq = shipmentRepository.maxShipmentSeqForOrg(orgId) + 1;
        Shipment shipment = Shipment.builder()
                .organizationId(orgId).userId(userId).orderId(order.getId())
                .shipmentSeq(seq)
                .shipmentNo(com.myplus.commerce.domain.InvoiceNumbers.shipment(seq))
                .invoiceNo(parcelInvoice)          // O7 D4: what this parcel is billed as
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
                    .orderItemId(item.getId()).quantity(e.getValue())
                    // O5d: record HOW it was entered, so "was this parcel actually checked?" is answerable
                    // later — which is the question that gets asked after a customer reports wrong goods.
                    .verified(verifiedByLine.getOrDefault(item.getId(), Boolean.FALSE))
                    .build());
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

    /**
     * Does this shop insist a packer scans, rather than types? (O5d, restored by O7 D3.)
     *
     * <p><b>Fails OPEN.</b> A settings hiccup must not stop a shop dispatching — that is a worse outage than an
     * unverified parcel, and the same call C3 makes for every non-safety flag.
     */
    private boolean scanRequired(Long orgId) {
        try {
            return settingsService != null && settingsService.getBoolFor(
                    orgId, com.myplus.marketplace.config.MarketplaceSettingsCatalog.PACK_SCAN_REQUIRED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Any line carrying a real quantity that was NOT scanned.
     *
     * <p>Zero-quantity lines are ignored deliberately: the workbench posts every line of the order, and one
     * the packer did not put in this parcel is not an unverified line — it is an absent one.
     */
    private static boolean hasUnverifiedLine(ShipmentDTO.Request req) {
        if (req == null || req.getLines() == null) return false;
        for (ShipmentDTO.LineRequest l : req.getLines()) {
            if (l == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
            if (!Boolean.TRUE.equals(l.getVerified())) return true;
        }
        return false;
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
        d.setInvoiceNo(s.getInvoiceNo());   // O7 D4: what this parcel is billed as
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
            dl.setDeliveredQuantity(l.getDeliveredQuantity());   // O7 D4: what actually reached the shop
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

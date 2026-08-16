package com.myplus.marketplace.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.DeliveryDTO;
import com.myplus.marketplace.entity.DeliveryRecord;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderItem;
import com.myplus.marketplace.entity.Shipment;
import com.myplus.marketplace.entity.ShipmentLine;
import com.myplus.marketplace.repository.DeliveryRecordRepository;
import com.myplus.marketplace.repository.OrderRepository;
import com.myplus.marketplace.repository.ShipmentRepository;

import lombok.RequiredArgsConstructor;

/**
 * OMS O7 D4 — what happened at the shop door, keyed by the warehouse admin.
 *
 * <h3>Three facts, kept separate on purpose</h3>
 * <ol>
 *   <li><b>What arrived</b> — per line, because a shop taking 8 of 10 or refusing a short-expiry batch is
 *       routine in this trade (design finding B2).</li>
 *   <li><b>What came back</b> — credited against the invoice THAT PARCEL went out on, through the trade
 *       contract, using the same {@code saleReturn} a counter return uses. No accounting happens here.</li>
 *   <li><b>What was collected</b> — settled through {@code receivePayment}. <b>Delivery and payment are
 *       different facts:</b> most of this trade is on credit, so goods arriving and money arriving are not the
 *       same event and are never inferred from each other.</li>
 * </ol>
 *
 * <h3>The driver has no device</h3>
 * So the signed paper invoice is the proof of delivery and this is the keyed summary of it. Everything written
 * here is attributed to the person who KEYED it, never to the driver — see {@link DeliveryRecord}.
 */
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryService.class);

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final DeliveryRecordRepository deliveryRepository;
    private final NotificationService notificationService;
    private final com.myplus.commerce.contracts.client.TradeClient tradeClient;

    /**
     * Record a delivery outcome for ONE parcel.
     *
     * <p><b>Idempotency lives here, not in the contract.</b> {@code returnLines} cannot be idempotent — a shop
     * genuinely can refuse the same product on two different deliveries — so the guard belongs where the
     * knowledge is: this service knows whether this parcel has already been keyed, and refuses a second
     * attempt rather than raising a second set of credit notes for one event.
     */
    @Transactional
    public DeliveryDTO record(Long orderId, DeliveryDTO dto, Long orgId, Long userId, String userName) {
        Order order = orderRepository.findByIdScoped(orderId, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (dto == null || dto.getShipmentId() == null)
            throw new ValidationException("Which parcel was delivered? An order may have several.");

        Shipment shipment = shipmentRepository.findById(dto.getShipmentId())
                .filter(s -> orderId.equals(s.getOrderId()))          // anti-IDOR: this parcel, this order
                .orElseThrow(() -> new ResourceNotFoundException("Parcel not found on this order"));

        if (!deliveryRepository.findByShipmentId(shipment.getId()).isEmpty())
            throw new ValidationException("Parcel " + shipment.getShipmentNo()
                    + " has already been recorded as delivered.");

        // ── 1. What arrived, per line ────────────────────────────────────────────────────────────────────
        java.util.Map<Long, Integer> deliveredByItem = new java.util.HashMap<>();
        if (dto.getLines() != null) {
            for (DeliveryDTO.Line l : dto.getLines()) {
                if (l == null || l.getOrderItemId() == null || l.getDeliveredQuantity() == null) continue;
                deliveredByItem.put(l.getOrderItemId(), Math.max(0, l.getDeliveredQuantity()));
            }
        }

        List<com.myplus.commerce.contracts.dto.SaleReturnLine> comingBack = new ArrayList<>();
        java.util.Map<Long, OrderItem> itemById = new java.util.HashMap<>();
        for (OrderItem it : order.getItems()) itemById.put(it.getId(), it);

        int totalSent = 0, totalTaken = 0;
        for (ShipmentLine sl : shipment.getLines()) {
            int sent = sl.getQuantity() == null ? 0 : sl.getQuantity();
            // Absent from the payload means "all of it arrived" — the common case, and the one the screen
            // pre-fills. A line the admin actively zeroes is a refusal and arrives as 0.
            int taken = deliveredByItem.containsKey(sl.getOrderItemId())
                    ? Math.min(deliveredByItem.get(sl.getOrderItemId()), sent) : sent;
            sl.setDeliveredQuantity(taken);
            totalSent += sent;
            totalTaken += taken;

            int back = sent - taken;
            if (back > 0) {
                OrderItem item = itemById.get(sl.getOrderItemId());
                if (item == null || item.getProductId() == null)
                    throw new ValidationException("Cannot credit a returned line with no product.");
                comingBack.add(com.myplus.commerce.contracts.dto.SaleReturnLine.builder()
                        .productId(item.getProductId()).quantity((float) back).build());
                // The order owes these units again: they went out, came back, and were never delivered.
                item.setQuantityShipped(Math.max(0, nz(item.getQuantityShipped()) - back));
            }
        }

        String outcome = totalTaken == 0 ? "REFUSED" : (totalTaken < totalSent ? "PARTIAL" : "DELIVERED");

        // ── 2. What came back → credit notes, through the contract ───────────────────────────────────────
        List<String> creditNotes = new ArrayList<>();
        if (!comingBack.isEmpty()) {
            if (shipment.getInvoiceNo() == null || shipment.getInvoiceNo().isBlank())
                throw new ValidationException("This parcel has no invoice, so nothing can be credited back.");
            // NOT best-effort. The goods are physically back and the customer has been told they are not
            // paying for them; if the credit note cannot be raised, the delivery must not be recorded either,
            // or the books claim a sale that everyone involved knows did not happen.
            List<String> raised = asOrg(orgId, () -> tradeClient.returnLines(
                    com.myplus.commerce.contracts.dto.SaleReturnRequest.builder()
                            .invoiceNo(shipment.getInvoiceNo())
                            .reason("Refused at delivery" + (dto.getNote() != null ? ": " + dto.getNote() : ""))
                            .lines(comingBack)
                            .build()));
            if (raised != null) creditNotes.addAll(raised);
        }

        // ── 3. The record ────────────────────────────────────────────────────────────────────────────────
        DeliveryRecord rec = deliveryRepository.save(DeliveryRecord.builder()
                .organizationId(orgId).orderId(orderId).shipmentId(shipment.getId())
                .invoiceNo(shipment.getInvoiceNo())
                // O7 D5 — WHO to credit when this collection is remitted, stamped at write from the order we
                // already hold. Deriving it at settlement time would put a join on the one list a warehouse
                // reads every working day, and would break the moment the order row was archived.
                .customerId(order.getCustomerId())
                .customerName(order.getCustomerName())
                .outcome(outcome)
                .settlement(dto.getSettlement())
                .amountCollected(dto.getAmountCollected())
                .creditNotes(creditNotes.isEmpty() ? null : String.join(",", creditNotes))
                .deliveredBy(dto.getDeliveredBy())
                .recordedByUserId(userId).recordedByName(userName)
                .note(dto.getNote())
                .recordedAt(java.time.LocalDateTime.now())
                .build());

        shipment.setStatus(outcome);
        shipmentRepository.save(shipment);

        // The header follows the lines, as it has since O5b — a delivery is not something you type either.
        // Only when something actually arrived: a wholly refused parcel leaves the order still owing.
        if (totalTaken > 0 && order.getFulfilmentStatus() != null
                && order.getFulfilmentStatus().canMoveTo(FulfilmentStatus.DELIVERED)) {
            order.setFulfilmentStatus(FulfilmentStatus.DELIVERED);
        }
        Order savedOrder = orderRepository.save(order);
        notificationService.notify(savedOrder, outcome,
                "Delivery recorded by " + (userName == null ? "staff" : userName)
                        + (creditNotes.isEmpty() ? "" : " — credit " + String.join(",", creditNotes)));

        LOG.info("O7 D4: parcel {} on order {} keyed {} ({} of {} taken), credit notes {}",
                shipment.getShipmentNo(), orderId, outcome, totalTaken, totalSent, creditNotes);

        DeliveryDTO out = new DeliveryDTO();
        out.setShipmentId(shipment.getId());
        out.setOutcome(outcome);
        out.setCreditNotes(creditNotes);
        out.setSettlement(rec.getSettlement());
        out.setAmountCollected(rec.getAmountCollected());
        out.setDeliveredBy(rec.getDeliveredBy());
        out.setRecordedByName(rec.getRecordedByName());
        out.setRecordedAt(rec.getRecordedAt());
        return out;
    }

    /**
     * The outcomes keyed against one order, oldest first.
     *
     * <p>Returns DTOs, not entities. It returned {@code List<DeliveryRecord>} — a JPA entity straight out of a
     * controller, which §1.5 forbids and which shipped {@code organizationId} and the raw row id to the
     * browser. D1 caught and fixed exactly this shape (§8.1b #4); D4 reintroduced it. Field names are
     * unchanged, so every existing reader is unaffected.
     */
    @Transactional(readOnly = true)
    public List<com.myplus.marketplace.dto.DeliveryRecordDTO> forOrder(Long orderId, Long orgId, Long userId) {
        orderRepository.findByIdScoped(orderId, orgId, userId)      // anti-IDOR before reading its history
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return deliveryRepository.findByOrderIdOrderByRecordedAtAsc(orderId).stream()
                .map(com.myplus.marketplace.dto.DeliveryRecordDTO::of).toList();
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    /**
     * Stamp the tenant on the outbound call — the admin's identity does not travel to business-service.
     *
     * <p>Delegates to {@link com.myplus.marketplace.support.AsOrg}: this method existed here and, identically,
     * in {@code DispatchInvoiceService}, and D5 wanted a third copy. It decides which identity another service
     * sees, so two definitions would be two answers to "who wrote this" on whichever path was not edited.
     */
    private <T> T asOrg(Long org, java.util.function.Supplier<T> call) {
        return com.myplus.marketplace.support.AsOrg.call(org, call);
    }
}

package com.myplus.marketplace.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.DeliveryDTO;
import com.myplus.marketplace.dto.DriverSettlementDTO;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.dto.RoundKeyDTO;
import com.myplus.marketplace.dto.ShipmentDTO;

import lombok.RequiredArgsConstructor;

/**
 * OMS O8 slice 5 — keys a whole delivery round back in from the marked-up sheet.
 *
 * <h3>A facade, and nothing more</h3>
 * Every rule this touches already exists. {@link DeliveryService#record} knows what a delivery means — short
 * quantities, credit notes, the outcome, the audit; {@link DriverSettlementService#settle} knows what settling
 * means — the claim, the receipts, the variance, the period lock. This class composes the two in the order the
 * paper does and <b>adds no money logic of its own</b>. That is deliberate: a second way to record a delivery or
 * clear a receivable is how two paths start disagreeing about the same round.
 *
 * <h3>Why the stops are keyed before anything is settled</h3>
 * Settling is all-or-nothing across the collections it is handed. So the deliveries are recorded first, their
 * ids gathered, and the settlement raised once at the end. If a stop cannot be keyed it is REPORTED and the
 * round continues — an operator needs to see which shop is wrong, not have twenty-eight good stops refused
 * because of one. If nothing at all could be keyed, nothing is settled.
 *
 * <h3>Running it twice is safe</h3>
 * A stop whose parcel is already delivered is skipped with a reason rather than keyed again. That matters
 * because this is one button over twenty-nine stops: an operator who is unsure whether it went through will
 * press it again, and the answer has to be "nothing more to do" rather than a second set of deliveries.
 */
@Service
@RequiredArgsConstructor
public class RoundKeyingService {

    private static final Logger LOG = LoggerFactory.getLogger(RoundKeyingService.class);

    private final OrderService orderService;
    private final DeliveryService deliveryService;
    private final DriverSettlementService driverSettlementService;

    @Transactional
    public RoundKeyDTO.Result keyRound(RoundKeyDTO req, Long orgId, Long userId, String userName) {
        if (req == null || req.getStops() == null || req.getStops().isEmpty())
            throw new ValidationException("Which stops? Key at least one line of the sheet.");

        List<Long> deliveryIds = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        BigDecimal declared = BigDecimal.ZERO;
        int keyed = 0;

        for (RoundKeyDTO.Stop stop : req.getStops()) {
            if (stop == null || stop.getOrderId() == null) continue;

            OrderDTO order;
            try {
                order = orderService.get(stop.getOrderId(), orgId, userId);
            } catch (RuntimeException notFound) {
                // Scoped read: another tenant's order, or a deleted one, reads as absent. Reported, not thrown.
                skipped.add("#" + stop.getOrderId() + " — not found");
                continue;
            }

            String label = order.getOrderNo() != null ? order.getOrderNo() : ("#" + stop.getOrderId());

            ShipmentDTO parcel = dispatchedParcel(order);
            if (parcel == null) {
                // Either nothing has gone out yet, or every parcel is already delivered. Both are ordinary and
                // both mean "nothing for this button to do here" — the second is what makes a re-run safe.
                skipped.add(label + " — no parcel awaiting an outcome");
                continue;
            }

            BigDecimal collected = stop.getAmountCollected() == null ? BigDecimal.ZERO : stop.getAmountCollected();
            if (collected.signum() < 0) {
                skipped.add(label + " — a negative collection is not a collection");
                continue;
            }

            DeliveryDTO delivery = new DeliveryDTO();
            delivery.setShipmentId(parcel.getId());
            delivery.setDeliveredBy(req.getSalesman());
            delivery.setAmountCollected(collected);
            delivery.setSettlement(settlementFor(order, collected));
            // EVERYTHING DISPATCHED WAS DELIVERED. The sheet has no returns column, so keying from it cannot
            // invent one — see RoundKeyDTO. A stop where goods came back is keyed on that order individually,
            // and this run then finds it delivered and skips it.
            delivery.setLines(order.getItems() == null ? List.of() : order.getItems().stream()
                    .map(l -> {
                        DeliveryDTO.Line dl = new DeliveryDTO.Line();
                        dl.setOrderItemId(l.getId());
                        dl.setDeliveredQuantity(l.getQuantityShipped());
                        return dl;
                    })
                    .filter(dl -> dl.getDeliveredQuantity() != null && dl.getDeliveredQuantity() > 0)
                    .toList());

            if (delivery.getLines().isEmpty()) {
                skipped.add(label + " — the parcel records no quantities");
                continue;
            }

            try {
                DeliveryDTO out = deliveryService.record(stop.getOrderId(), delivery, orgId, userId, userName);
                if (out.getId() == null) {
                    // Would have been silent before slice 5 added the id: the delivery is recorded but cannot be
                    // settled, so say so rather than drop the cash quietly out of the remittance.
                    skipped.add(label + " — keyed, but the collection could not be identified to settle");
                } else {
                    deliveryIds.add(out.getId());
                    declared = declared.add(collected);
                    keyed++;
                }
            } catch (RuntimeException ex) {
                // One shop's refusal must not lose the other twenty-eight. Named in the log because a remote
                // call sits underneath this and its failures are otherwise hard to read afterwards.
                LOG.warn("O8 slice 5: could not key {} on the round for org {}", label, orgId, ex);
                skipped.add(label + " — " + (ex.getMessage() == null ? "refused" : ex.getMessage()));
            }
        }

        RoundKeyDTO.Result result = RoundKeyDTO.Result.builder()
                .keyed(keyed).skipped(skipped).declared(declared)
                .receipts(new ArrayList<>())
                .build();

        if (deliveryIds.isEmpty()) {
            // Nothing was keyed, so there is nothing to settle and no variance to explain. Returning the
            // reasons is the whole value of the call in this case.
            LOG.info("O8 slice 5: nothing keyed on the round for org {} ({} skipped)", orgId, skipped.size());
            return result;
        }

        // ── settle, once, over everything just keyed ─────────────────────────────────────────────────────
        //
        // The count is what the CASHIER counted, never the sum above: the difference between the two is the
        // variance, and a settlement that derived the count from the declarations could never show one. A nil
        // stop is included and raises no receipt — the fix that made a sheet listing every stop settleable.
        DriverSettlementDTO settle = new DriverSettlementDTO();
        settle.setDeliveryIds(deliveryIds);
        settle.setCountedAmount(req.getCountedAmount() != null ? req.getCountedAmount() : declared);
        settle.setDepositReference(req.getDepositReference());
        settle.setNote(req.getNote());

        DriverSettlementDTO settled = driverSettlementService.settle(settle, orgId, userId, userName);
        result.setSettlementNo(settled.getSettlementNo());
        if (settled.getReceipts() != null) result.setReceipts(new ArrayList<>(settled.getReceipts()));

        LOG.info("O8 slice 5: keyed {} stop(s) for org {}, settled as {} (declared {}, {} skipped)",
                keyed, orgId, settled.getSettlementNo(), declared, skipped.size());
        return result;
    }

    /** The one parcel still awaiting an outcome. Null when nothing went out, or it is all already keyed. */
    private static ShipmentDTO dispatchedParcel(OrderDTO order) {
        if (order.getShipments() == null) return null;
        return order.getShipments().stream()
                .filter(s -> "DISPATCHED".equalsIgnoreCase(s.getStatus()))
                .findFirst().orElse(null);
    }

    /**
     * How the stop settled, in the vocabulary {@code DeliveryService} already uses.
     *
     * <p>Derived rather than asked for: the sheet records an amount, not a category, and making the operator
     * pick one as well would be asking them to restate what they just typed — and to get it wrong.
     */
    private static String settlementFor(OrderDTO order, BigDecimal collected) {
        if (collected.signum() <= 0) return "CREDIT";                         // the sheet's "CR"
        BigDecimal owedForThisOrder = order.getTotal() == null ? BigDecimal.ZERO : order.getTotal();
        return collected.compareTo(owedForThisOrder) >= 0 ? "PAID" : "PARTIAL";
    }
}

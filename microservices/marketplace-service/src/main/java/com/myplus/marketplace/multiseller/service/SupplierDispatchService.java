package com.myplus.marketplace.multiseller.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.repository.OrderRepository;
import com.myplus.marketplace.repository.ShipmentRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-4b — a supplier's dispatch deadline (R20.5 "SLA"; slice doc {@code mkt-4b-supplier-dispatch.md}).
 *
 * <p>A part of supplier stock must leave within {@link #SHIP_WITHIN_HOURS} hours of the supplier's Accept. The
 * deadline is stamped once, at Accept ({@link #stamp}); what has been sent is read from the supplier's store order and
 * its parcels (MKT-3b: read, never copied). Every other part has no deadline and is untouched.
 *
 * <p>Once a deadline passes, {@link #sweep} settles the part once: ON_TIME (a parcel left by then), LATE (nothing had
 * left; audited, the shopper is told MaxTheService is chasing it, the scorecard counts it), or VOID (the part or its
 * store order ended first). Like every flag on the scorecard it is a record, never a sanction (R12.4).
 */
@Service
@RequiredArgsConstructor
public class SupplierDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(SupplierDispatchService.class);

    /** The source's supplier terms put acceptance at 15–20 minutes; the parcel must then leave within a day. */
    public static final int SHIP_WITHIN_HOURS = 24;
    public static final String DUE = "DUE", ON_TIME = "ON_TIME", LATE = "LATE", VOID = "VOID";
    static final int BATCH = 100;
    private static final Set<FulfilmentStatus> SENT =
            Set.of(FulfilmentStatus.PARTIALLY_SHIPPED, FulfilmentStatus.SHIPPED, FulfilmentStatus.DELIVERED);
    private static final Set<FulfilmentStatus> ENDED =
            Set.of(FulfilmentStatus.CANCELLED, FulfilmentStatus.REJECTED);

    private final MarketplaceSellerOrderRepository sellerOrders;
    private final OrderRepository storeOrders;
    private final ShipmentRepository shipments;
    private final MarketplaceAuditService audit;
    private final PlatformTransactionManager txManager;

    /** What the shopper and the supplier are shown: time left to send it (null when there is none to show), and late. */
    public record Clock(Long secondsToShip, boolean late) {
        static final Clock NONE = new Clock(null, false);
    }

    /** At Accept: a part of supplier stock gets its deadline; any other part keeps none. */
    public static void stamp(MarketplaceSellerOrder so, List<MarketplaceOrderLine> ls, LocalDateTime acceptedAt) {
        if (!MarketplaceCheckoutService.supplierStock(ls) || acceptedAt == null) return;
        so.setDispatchBy(acceptedAt.plusHours(SHIP_WITHIN_HOURS));
        so.setDispatchStatus(DUE);
    }

    /** The clock for a part whose store order is already read ({@code store} may be null). */
    public Clock clock(MarketplaceSellerOrder so, Order store, LocalDateTime now) {
        if (so.getDispatchBy() == null || so.getDispatchStatus() == null || VOID.equals(so.getDispatchStatus())
                || !SellerOrder.ACCEPTED.name().equals(so.getAcceptanceStatus()) || so.getDeliveredAt() != null
                || sentAt(store, now) != null || (store != null && ENDED.contains(store.getFulfilmentStatus())))
            return Clock.NONE;                                   // nothing to send any more, or it has left
        long left = Duration.between(now, so.getDispatchBy()).getSeconds();
        return new Clock(Math.max(0, left), LATE.equals(so.getDispatchStatus()) || left <= 0);
    }

    /**
     * The same, reading the store order: two reads, and only for a supplier's part still to be sent (the seller's
     * list stays two queries per page for every other part).
     */
    public Clock clock(MarketplaceSellerOrder so) {
        if (so.getDispatchBy() == null || !(DUE.equals(so.getDispatchStatus()) || LATE.equals(so.getDispatchStatus()))
                || !SellerOrder.ACCEPTED.name().equals(so.getAcceptanceStatus()) || so.getDeliveredAt() != null)
            return Clock.NONE;
        return clock(so, storeOrder(so), LocalDateTime.now());
    }

    /** Settles every part past its deadline, each in its own short transaction. @return how many were settled */
    public int sweep() {
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (MarketplaceSellerOrder so : sellerOrders.findByDispatchStatusAndDispatchByBeforeOrderByDispatchByAsc(
                DUE, now, PageRequest.of(0, BATCH))) {
            if (settle(so.getId(), now)) n++;
        }
        return n;
    }

    boolean settle(Long id, LocalDateTime now) {
        String[] late = new String[1];
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                MarketplaceSellerOrder so = sellerOrders.findById(id).orElse(null);
                if (so == null || !DUE.equals(so.getDispatchStatus())) return;            // someone else got there
                so.setDispatchStatus(verdict(so, storeOrder(so), now));
                sellerOrders.save(so);
                if (LATE.equals(so.getDispatchStatus())) late[0] = String.valueOf(so.getSellerOrganizationId());
            });
        } catch (OptimisticLockingFailureException concurrent) {
            return false;                                                                  // the other instance won
        } catch (RuntimeException e) {
            LOG.warn("MKT dispatch check of seller order {} failed; the next sweep retries: {}", id, e.toString());
            return false;
        }
        if (late[0] != null) {
            MarketplaceSellerOrder so = sellerOrders.findById(id).orElseThrow();
            audit.event("MKT_DISPATCH_LATE", "MKT_SELLER_ORDER", so.getStoreOrderNo(), so.getSellerOrganizationId(),
                    MarketplaceAuditService.Actor.SYSTEM, DUE, LATE, null,
                    "Not sent within " + SHIP_WITHIN_HOURS + " hours of acceptance (due " + so.getDispatchBy() + ").");
        }
        return true;
    }

    /** ON_TIME, LATE or VOID for a part whose deadline has passed. */
    String verdict(MarketplaceSellerOrder so, Order store, LocalDateTime now) {
        if (!SellerOrder.ACCEPTED.name().equals(so.getAcceptanceStatus())
                && !SellerOrder.HANDED_OVER.name().equals(so.getAcceptanceStatus())) return VOID;
        if (so.getDeliveredAt() != null) return so.getDeliveredAt().isAfter(so.getDispatchBy()) ? LATE : ON_TIME;
        LocalDateTime sent = sentAt(store, now);
        if (sent != null) return sent.isAfter(so.getDispatchBy()) ? LATE : ON_TIME;
        if (store != null && ENDED.contains(store.getFulfilmentStatus())) return VOID;
        return LATE;
    }

    /**
     * When the first parcel left; null while nothing has. A store order moved to SHIPPED without a parcel record has
     * no time of its own, so it counts as sent when it is first seen sent ({@code now}).
     */
    LocalDateTime sentAt(Order store, LocalDateTime now) {
        if (store == null) return null;
        LocalDateTime first = shipments.findByOrderIdOrderByShipmentSeqAsc(store.getId()).stream()
                .filter(sh -> !"CANCELLED".equals(sh.getStatus()))
                .map(sh -> sh.getShippedAt() != null ? sh.getShippedAt() : sh.getCreatedAt())
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        if (first != null) return first;
        return SENT.contains(store.getFulfilmentStatus()) ? now : null;
    }

    /** The supplier's own store order, as checkout reads it; null before Accept. */
    Order storeOrder(MarketplaceSellerOrder so) {
        if (so.getStoreOrderId() == null) return null;
        return storeOrders.findById(so.getStoreOrderId())
                .filter(o -> Objects.equals(o.getOrganizationId(), so.getSellerOrganizationId())).orElse(null);
    }
}

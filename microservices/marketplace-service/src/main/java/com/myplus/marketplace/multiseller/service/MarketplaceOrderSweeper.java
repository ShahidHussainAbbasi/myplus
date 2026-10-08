package com.myplus.marketplace.multiseller.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e — the acceptance window's clock (source §10: "the hold expires and the stock is released").
 *
 * <p>Three passes, each bounded, each row in its own short transaction so one bad row cannot stop the rest:
 * <ol>
 *   <li><b>Expire</b>: OFFERED past {@code accept_by + GRACE} → EXPIRED, hold released; the order CANCELLED once no
 *       other part goes ahead (MKT-2a).</li>
 *   <li><b>Orphans</b>: UNASSIGNED older than {@link #ORPHAN_AFTER} (a crash between checkout's two transactions)
 *       → CANCELLED, release by key (harmless when nothing was held).</li>
 *   <li><b>Release retries</b>: a promise that ended with {@code held} still true (a release that failed).</li>
 * </ol>
 *
 * <h3>Safe with two instances, without a lock table</h3>
 * Every transition is made on a freshly read row with its {@code @Version}: when two instances pick the same row,
 * one commits and the other's optimistic-lock failure is skipped. Releases are idempotent on the hold key.
 *
 * <h3>Why the grace</h3>
 * {@link SellerOrderService#accept} refuses AT the deadline, but an accept that started a second earlier is still
 * recording its sale (bounded by the client timeouts, seconds). The sweeper waits {@link #GRACE} past the deadline
 * so it can never expire an order whose sale is being written.
 */
@Component
@RequiredArgsConstructor
public class MarketplaceOrderSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplaceOrderSweeper.class);

    static final Duration GRACE = Duration.ofSeconds(30);
    static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);
    static final int BATCH = 100;
    static final String EXPIRED_FOR_SHOPPER = "The seller did not confirm in time.";
    static final String ORPHAN_FOR_SHOPPER = "The order could not be placed. Please try again.";

    private final MarketplaceSellerOrderRepository sellerOrders;
    private final MarketplaceOrderRepository orders;
    private final SellerOrderService sellerOrderService;
    private final MarketplacePaymentService payments;
    private final PlatformTransactionManager txManager;
    /** MKT-2b: an expired part is a shortage (the seller did not answer); its alternatives and proposals have a clock too. */
    private final MarketplaceShortageService shortages;

    @Scheduled(fixedDelayString = "${mkt.orders.sweep-ms:30000}", initialDelayString = "${mkt.orders.sweep-initial-ms:30000}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int expired = 0, orphans = 0, released = 0;
        for (MarketplaceSellerOrder so : sellerOrders.findByAcceptanceStatusAndAcceptByBeforeOrderByAcceptByAsc(
                SellerOrder.OFFERED.name(), now.minus(GRACE), PageRequest.of(0, BATCH))) {
            if (end(so.getId(), SellerOrder.OFFERED, SellerOrder.EXPIRED, EXPIRED_FOR_SHOPPER)) expired++;
        }
        for (MarketplaceSellerOrder so : sellerOrders.findByAcceptanceStatusAndCreatedAtBefore(
                SellerOrder.UNASSIGNED.name(), now.minus(ORPHAN_AFTER), PageRequest.of(0, BATCH))) {
            if (end(so.getId(), SellerOrder.UNASSIGNED, SellerOrder.CANCELLED, ORPHAN_FOR_SHOPPER)) orphans++;
        }
        for (MarketplaceSellerOrder so : sellerOrders.findByHeldTrueAndAcceptanceStatusIn(
                List.of(SellerOrder.EXPIRED.name(), SellerOrder.REJECTED.name(), SellerOrder.CANCELLED.name()),
                PageRequest.of(0, BATCH))) {
            sellerOrderService.release(so);
            released++;
        }
        int shortageWork = shortages.sweep();                        // MKT-2b: proposals, interrupted reroutes, their holds
        int reconciled = payments.reconcile();                       // lost charge answers and refunds not yet done
        if (expired + orphans + released + shortageWork + reconciled > 0)
            LOG.info("MKT sweep: {} expired, {} orphans cancelled, {} release retries, {} shortage steps, {} payments reconciled",
                    expired, orphans, released, shortageWork, reconciled);
    }

    /**
     * One row: transition if it is still in {@code from}, cancel the parent, commit; THEN release the hold.
     * @return true when this instance made the transition
     */
    boolean end(Long sellerOrderId, SellerOrder from, SellerOrder to, String reasonForShopper) {
        MarketplaceSellerOrder[] done = new MarketplaceSellerOrder[1];
        boolean[] moving = new boolean[1];
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                MarketplaceSellerOrder so = sellerOrders.findById(sellerOrderId).orElse(null);
                if (so == null || !from.name().equals(so.getAcceptanceStatus())) return;   // someone else got there
                MarketplaceCheckoutService.move(so, to);
                MarketplaceOrder o = orders.findById(so.getMktOrderId()).orElse(null);
                if (o != null && to == SellerOrder.EXPIRED) {
                    // MKT-2b: the seller did not answer: recorded, and moved to another seller when the operator allows
                    moving[0] = shortages.begin(so, o, sellerOrderService.partsOf(o.getId(), so),
                            com.myplus.marketplace.multiseller.entity.MarketplaceShortage.Cause.NO_RESPONSE,
                            "Not accepted within the acceptance window.", reasonForShopper);
                    orders.save(o);
                } else if (o != null && !"CANCELLED".equals(o.getStatus())) {
                    // MKT-2a: the order ends only when no other part is still going ahead
                    MarketplaceCheckoutService.follow(o, sellerOrderService.partsOf(o.getId(), so), reasonForShopper);
                    orders.save(o);
                }
                sellerOrders.save(so);
                done[0] = so;
            });
        } catch (OptimisticLockingFailureException concurrent) {
            return false;                                              // the other instance (or the seller) won
        } catch (RuntimeException e) {
            LOG.warn("MKT sweep could not end seller order {}: {}", sellerOrderId, e.toString());
            return false;
        }
        if (done[0] == null) return false;
        // after the commit; by key, so a hold that was never taken is a harmless no-op
        MarketplaceSellerOrder fresh = sellerOrders.findById(sellerOrderId).orElse(done[0]);
        fresh.setHeld(true);   // force the attempt: an orphan's flag is false although the hold may exist
        sellerOrderService.release(fresh);
        sellerOrderService.afterShort(sellerOrderId, moving[0]);      // the part's money back once, or another seller looked for
        return true;
    }
}

package com.myplus.marketplace.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderItem;

import lombok.RequiredArgsConstructor;

/**
 * OMS O7 D1c — the stock a CONFIRMED order has promised, and giving it back.
 *
 * <h3>Why its own class</h3>
 * Both {@link OrderService} (confirm / reject / cancel) and {@link ShipmentService} (dispatch) need this, and
 * {@code OrderService} already depends on {@code ShipmentService} — so putting it in either would make the two
 * mutually dependent. Exactly the reasoning that gave {@code DispatchInvoiceService} its own class in D1.
 *
 * <h3>What it closes</h3>
 * §8.1 departure #1. Until now a confirmed order held nothing, so two orders confirmed for the last carton
 * both confirmed and the second failed at dispatch — with the rep gone and the shopkeeper already told. A
 * confirmed order is a promise to a named customer; this is what backs it.
 *
 * <h3>The hold is taken through the TRADE contract, never against inventory directly</h3>
 * business-service owns what stock means for a trade sale. A channel holding inventory on its own account is
 * what produced holds with no invoice behind them, and <b>O1 deleted that saga</b>. That a hold now expires
 * (O5a) makes this safe to attempt again; it does not make a second stock authority a good idea.
 */
@Service
@RequiredArgsConstructor
public class OrderStockHoldService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderStockHoldService.class);

    private final TradeClient tradeClient;

    /**
     * The key this order's hold is filed under.
     *
     * <p>Derived from the ORDER, never from the request making the call, so confirm, re-confirm and a retry
     * after a timeout all address the same hold. inventory-service is idempotent on it — "a retried reserve
     * with the same key returns the existing hold, never double-holds" — which is what makes a repeated
     * confirm harmless rather than a way to sterilise the stock twice over.
     */
    public static String keyFor(Order order) { return "SO-" + order.getId() + "-HOLD"; }

    /**
     * Set aside what this order still owes.
     *
     * <h3>OUTSTANDING quantity, not ordered quantity</h3>
     * {@code quantity - quantityShipped}. After a partial dispatch the goods already sent are the sale's
     * business, not the hold's; re-holding the full ordered amount would sterilise stock that has already left
     * the building. Partial dispatch is a distributor's normal week, so this is the common path, not an edge.
     *
     * <h3>A failure does not refuse anything</h3>
     * Out of stock is an answer the admin is entitled to act on — a distributor expecting a delivery tomorrow
     * may confirm against it knowingly — and an inventory outage must not read as a business refusal to the
     * person at the screen. Confirming without a hold is exactly the pre-D1c behaviour, so the floor here is
     * "no worse than before". The reverse is what must never happen: reporting a hold that was not taken.
     *
     * @return the operator-facing reason the stock could NOT be held, or null when it was held (or when there
     *         was nothing outstanding left to hold)
     */
    public String hold(Order order, Long orgId) {
        try {
            List<StockReservationLine> lines = new ArrayList<>();
            for (OrderItem item : order.getItems()) {
                if (item.getProductId() == null || item.getQuantity() == null) continue;
                int shipped = item.getQuantityShipped() == null ? 0 : item.getQuantityShipped();
                int outstanding = item.getQuantity() - shipped;
                if (outstanding <= 0) continue;
                lines.add(new StockReservationLine(item.getProductId(), BigDecimal.valueOf(outstanding)));
            }
            if (lines.isEmpty()) return null;          // nothing left to promise

            // The SHARED runner DispatchInvoiceService already uses — not a fourth private copy of it.
            StockHoldResponse r = com.myplus.marketplace.support.AsOrg.call(orgId, () ->
                    tradeClient.holdStock(StockHoldRequest.builder()
                            .organizationId(orgId)
                            .holdKey(keyFor(order))
                            .lines(lines)
                            .build()));

            if (r != null && r.isHeld()) return null;
            String why = (r == null) ? "inventory did not answer" : r.getReason();
            LOG.info("D1c: order {} has NO stock hold — {}", order.getOrderNo(), why);
            return why;
        } catch (Exception e) {
            LOG.warn("D1c: could not hold stock for order {}; it stands regardless: {}",
                    order.getOrderNo(), e.toString());
            return "the stock could not be checked";
        }
    }

    /**
     * Give this order's held stock back.
     *
     * <p>Called whenever the promise ends, whichever way: rejected, cancelled, or dispatched — at dispatch the
     * sale takes its own hold, so this one must go or the same goods are held twice.
     *
     * <p>Best effort by design. The expiry sweeper is the backstop, which is what O5a built it for, and a
     * release that throws must never fail a rejection the admin has already made.
     */
    public void release(Order order, Long orgId) {
        try {
            com.myplus.marketplace.support.AsOrg.run(orgId, () -> tradeClient.releaseHold(keyFor(order)));
        } catch (Exception e) {
            LOG.warn("D1c: release of the hold on order {} failed; the sweeper will collect it: {}",
                    order.getOrderNo(), e.toString());
        }
    }
}

package com.myplus.marketplace.multiseller.service;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e2 — what a signed-in customer does with their orders: list them, and cancel one the seller has not answered.
 * Every read and write is scoped to orders PROVEN to be theirs ({@code customer_id}); another account's order reads
 * exactly like one that does not exist.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceAccountService {

    static final String CANCELLED_BY_YOU = "You cancelled this order.";

    private final MarketplaceOrderRepository orders;
    private final MarketplaceSellerOrderRepository sellerOrders;
    private final com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository lines;
    private final MarketplaceCheckoutService checkout;
    private final SellerOrderService sellerSide;
    private final MarketplacePaymentService payments;
    private final PlatformTransactionManager txManager;
    /** G-16 (R22.4): every marketplace action audited, filed under the seller it concerns. */
    private final MarketplaceAuditService audit;

    public PageResponse<MarketplaceOrderDTOs.AccountOrderView> myOrders(MarketplaceCustomer c, Integer page, Integer size) {
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 20 : Math.min(size, 50));
        return PageResponse.of(orders.findByCustomerIdOrderByCreatedAtDesc(c.getId(), p), this::accountView);
    }

    /**
     * Only while no seller has accepted (every part still OFFERED, or already ended). After an acceptance it is a
     * support case (MKT-1f). MKT-2a: every waiting part is cancelled together, in one transaction.
     */
    public MarketplaceOrderDTOs.AccountOrderView cancel(MarketplaceCustomer c, String orderNo, String reason) {
        MarketplaceOrder o = own(c, orderNo);
        if ("CANCELLED".equals(o.getStatus())) return accountView(o);             // a double click
        List<MarketplaceSellerOrder> parts = sellerOrders.findByMktOrderId(o.getId());
        if (parts.isEmpty()) throw new ResourceNotFoundException("No such order.");
        if (parts.stream().anyMatch(p -> MarketplaceCheckoutService.ACCEPTED_PARTS.contains(p.getAcceptanceStatus())))
            throw new ValidationException(ALREADY_CONFIRMED);
        List<MarketplaceSellerOrder> waiting = parts.stream()
                .filter(p -> SellerOrder.OFFERED.name().equals(p.getAcceptanceStatus())).toList();
        if (waiting.isEmpty()) throw new ValidationException("This order can no longer be cancelled.");
        String why = reason == null || reason.isBlank() ? CANCELLED_BY_YOU
                : (CANCELLED_BY_YOU + " " + reason.trim()).substring(0, Math.min(300, CANCELLED_BY_YOU.length() + 1 + reason.trim().length()));
        java.util.Map<Long, Integer> seen = new java.util.HashMap<>();
        waiting.forEach(p -> seen.put(p.getId(), p.getVersion()));
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                List<MarketplaceSellerOrder> fresh = new java.util.ArrayList<>();
                for (Long id : seen.keySet()) {
                    MarketplaceSellerOrder f = sellerOrders.findById(id).orElseThrow();
                    if (!seen.get(id).equals(f.getVersion()) || !SellerOrder.OFFERED.name().equals(f.getAcceptanceStatus()))
                        throw new OptimisticLockingFailureException("seller order changed");
                    MarketplaceCheckoutService.move(f, SellerOrder.CANCELLED);
                    sellerOrders.save(f);
                    fresh.add(f);
                }
                MarketplaceOrder parent = orders.findById(o.getId()).orElseThrow();
                MarketplaceCheckoutService.follow(parent, sellerOrders.findByMktOrderId(o.getId()).stream()
                        .map(p -> fresh.stream().filter(f -> f.getId().equals(p.getId())).findFirst().orElse(p)).toList(), why);
                orders.save(parent);
            });
        } catch (OptimisticLockingFailureException raced) {
            // a seller answered (or the clock ran out) while the shopper pressed: say what happened instead
            if (sellerOrders.findByMktOrderId(o.getId()).stream()
                    .anyMatch(p -> MarketplaceCheckoutService.ACCEPTED_PARTS.contains(p.getAcceptanceStatus())))
                throw new ValidationException(ALREADY_CONFIRMED);
            return accountView(orders.findById(o.getId()).orElseThrow());
        }
        for (Long id : seen.keySet()) sellerSide.release(sellerOrders.findById(id).orElseThrow());   // after the commit: the stock goes back
        payments.refundIfCancelled(o.getId());                                    // a card order's money goes back, once
        for (MarketplaceSellerOrder p : waiting)
            audit.event("MKT_ORDER_CANCELLED", "MKT_ORDER", o.getOrderNo(), p.getSellerOrganizationId(), MarketplaceAuditService.Actor.CUSTOMER,
                    SellerOrder.OFFERED.name(), "CANCELLED", MarketplaceCheckoutService.partTotal(lines.findBySellerOrderIdOrderByIdAsc(p.getId())), why);
        return accountView(orders.findById(o.getId()).orElseThrow());
    }

    static final String ALREADY_CONFIRMED = "This order is already confirmed by the seller. Ask MaxTheService support to cancel it.";

    /** Theirs, or "No such order." — the same answer for another account's order and for one that does not exist. */
    private MarketplaceOrder own(MarketplaceCustomer c, String orderNo) {
        MarketplaceOrder o = orderNo == null ? null : orders.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT)).orElse(null);
        if (o == null || o.getCustomerId() == null || !o.getCustomerId().equals(c.getId()))
            throw new ResourceNotFoundException("No such order.");
        return o;
    }

    MarketplaceOrderDTOs.AccountOrderView accountView(MarketplaceOrder o) {
        MarketplaceOrderDTOs.OrderView v = checkout.view(o);
        List<MarketplaceOrderDTOs.PaymentView> ps = payments.history(o.getId()).stream()
                .map(p -> new MarketplaceOrderDTOs.PaymentView(p.getKind(), p.getStatus(), p.getAmount(), p.getReason(),
                        p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt()))
                .toList();
        List<MarketplaceOrderDTOs.PartView> parts = v.sellerOrders();
        boolean anyAccepted = parts.stream().anyMatch(p -> MarketplaceCheckoutService.ACCEPTED_PARTS.contains(p.status()));
        boolean canCancel = !"CANCELLED".equals(v.status()) && !anyAccepted
                && parts.stream().anyMatch(p -> SellerOrder.OFFERED.name().equals(p.status()));
        // MKT-1f: the delivery happens in the seller's store order; the marketplace keeps only WHEN (delivered_at). Shown,
        // never stored as a second status: CONFIRMED + delivered reads "DELIVERED" to the shopper. MKT-2a: once EVERY
        // accepted part is delivered, nothing still waiting; the date is the last delivery.
        List<MarketplaceOrderDTOs.PartView> accepted = parts.stream()
                .filter(p -> MarketplaceCheckoutService.ACCEPTED_PARTS.contains(p.status())).toList();
        boolean waiting = parts.stream().anyMatch(p -> MarketplaceCheckoutService.LIVE_PARTS.contains(p.status()));
        java.time.LocalDateTime delivered = accepted.isEmpty() || waiting || accepted.stream().anyMatch(p -> p.deliveredAt() == null)
                ? null : accepted.stream().map(MarketplaceOrderDTOs.PartView::deliveredAt).max(java.time.LocalDateTime::compareTo).orElse(null);
        String status = "CONFIRMED".equals(v.status()) && delivered != null ? "DELIVERED" : v.status();
        boolean canGetHelp = !"CANCELLED".equals(v.status()) && anyAccepted;
        return new MarketplaceOrderDTOs.AccountOrderView(v.orderNo(), status, v.paymentMode(), v.paymentStatus(), v.total(),
                v.cancelReason(), v.createdAt(), v.sellerName(), v.sellerOrderStatus(), v.secondsToAccept(), v.city(), v.lines(),
                ps, canCancel, delivered, canGetHelp, parts);
    }

    /** Re-used by the controller for a claim's answer. */
    public MarketplaceOrderDTOs.AccountOrderView view(MarketplaceOrder o) {
        return accountView(o);
    }

}

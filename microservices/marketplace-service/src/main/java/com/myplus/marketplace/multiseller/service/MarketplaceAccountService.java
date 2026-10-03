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
    private final MarketplaceCheckoutService checkout;
    private final SellerOrderService sellerSide;
    private final MarketplacePaymentService payments;
    private final PlatformTransactionManager txManager;

    public PageResponse<MarketplaceOrderDTOs.AccountOrderView> myOrders(MarketplaceCustomer c, Integer page, Integer size) {
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 20 : Math.min(size, 50));
        return PageResponse.of(orders.findByCustomerIdOrderByCreatedAtDesc(c.getId(), p), this::accountView);
    }

    /** Only while the seller has not answered (OFFERED). After acceptance it is a support case (MKT-1f). */
    public MarketplaceOrderDTOs.AccountOrderView cancel(MarketplaceCustomer c, String orderNo, String reason) {
        MarketplaceOrder o = own(c, orderNo);
        if ("CANCELLED".equals(o.getStatus())) return accountView(o);             // a double click
        MarketplaceSellerOrder so = sellerOrders.findByMktOrderId(o.getId()).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No such order."));
        if (SellerOrder.ACCEPTED.name().equals(so.getAcceptanceStatus()))
            throw new ValidationException("This order is already confirmed by the seller. Ask MaxTheService support to cancel it.");
        if (!SellerOrder.OFFERED.name().equals(so.getAcceptanceStatus()))
            throw new ValidationException("This order can no longer be cancelled.");
        String why = reason == null || reason.isBlank() ? CANCELLED_BY_YOU
                : (CANCELLED_BY_YOU + " " + reason.trim()).substring(0, Math.min(300, CANCELLED_BY_YOU.length() + 1 + reason.trim().length()));
        Long soId = so.getId();
        Integer seen = so.getVersion();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                MarketplaceSellerOrder fresh = sellerOrders.findById(soId).orElseThrow();
                if (!seen.equals(fresh.getVersion()) || !SellerOrder.OFFERED.name().equals(fresh.getAcceptanceStatus()))
                    throw new OptimisticLockingFailureException("seller order changed");
                MarketplaceCheckoutService.move(fresh, SellerOrder.CANCELLED);
                sellerOrders.save(fresh);
                MarketplaceOrder parent = orders.findById(fresh.getMktOrderId()).orElseThrow();
                MarketplaceCheckoutService.cancel(parent, why);
                orders.save(parent);
            });
        } catch (OptimisticLockingFailureException raced) {
            // the seller answered (or the clock ran out) while the shopper pressed: say what happened instead
            MarketplaceSellerOrder now = sellerOrders.findById(soId).orElseThrow();
            if (SellerOrder.ACCEPTED.name().equals(now.getAcceptanceStatus()))
                throw new ValidationException("This order is already confirmed by the seller. Ask MaxTheService support to cancel it.");
            return accountView(orders.findById(o.getId()).orElseThrow());
        }
        sellerSide.release(sellerOrders.findById(soId).orElseThrow());           // after the commit: the stock goes back
        payments.refundIfCancelled(o.getId());                                    // a card order's money goes back, once
        return accountView(orders.findById(o.getId()).orElseThrow());
    }

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
        boolean canCancel = !"CANCELLED".equals(v.status()) && SellerOrder.OFFERED.name().equals(v.sellerOrderStatus());
        // MKT-1f: the delivery happens in the seller's store order; the marketplace keeps only WHEN (delivered_at). Shown,
        // never stored as a second status: CONFIRMED + delivered reads "DELIVERED" to the shopper.
        java.time.LocalDateTime delivered = v.sellerOrderId() == null ? null
                : sellerOrders.findById(v.sellerOrderId()).map(MarketplaceSellerOrder::getDeliveredAt).orElse(null);
        String status = "CONFIRMED".equals(v.status()) && delivered != null ? "DELIVERED" : v.status();
        boolean canGetHelp = !"CANCELLED".equals(v.status()) && SellerOrder.ACCEPTED.name().equals(v.sellerOrderStatus());
        return new MarketplaceOrderDTOs.AccountOrderView(v.orderNo(), status, v.paymentMode(), v.paymentStatus(), v.total(),
                v.cancelReason(), v.createdAt(), v.sellerName(), v.sellerOrderStatus(), v.secondsToAccept(), v.city(), v.lines(),
                ps, canCancel, delivered, canGetHelp);
    }

    /** Re-used by the controller for a claim's answer. */
    public MarketplaceOrderDTOs.AccountOrderView view(MarketplaceOrder o) {
        return accountView(o);
    }

}

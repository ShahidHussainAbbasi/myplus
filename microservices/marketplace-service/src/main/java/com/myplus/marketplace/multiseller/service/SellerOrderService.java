package com.myplus.marketplace.multiseller.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Order;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.service.OrderService;
import com.myplus.marketplace.support.AsOrg;
import com.myplus.marketplace.support.DownstreamMessage;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e — the seller's side of a marketplace order: the queue, accept within the window, reject.
 * Contract: docs/slices/mkt-1e-checkout-acceptance.md
 *
 * <h3>Accept, in an order chosen so the stock is never left unprotected for a reason we could have checked</h3>
 * <ol>
 *   <li>Everything decidable locally first: own org, OFFERED, before the deadline, active seller, and the serial
 *       numbers a serial-tracked item needs. A refusal here changes nothing.</li>
 *   <li>Release the hold (the sale cannot consume it; O7 D1c's order), then record the sale in the SELLER's books
 *       ({@link OrderService#placeMarketplace}, idempotent on {@code MKT-SO-{id}}).</li>
 *   <li>A sale refusal re-holds (best effort) and leaves the order OFFERED, with the reason shown to the seller.</li>
 *   <li>Short transaction: ACCEPTED + the store order and invoice numbers; the parent CONFIRMED.</li>
 * </ol>
 * The sweeper never touches an OFFERED row until {@link MarketplaceOrderSweeper#GRACE} after its deadline, and
 * accept refuses AT the deadline, so an accept in flight cannot race an expiry (the sale call is bounded by its
 * client timeouts, well inside the grace).
 */
@Service
@RequiredArgsConstructor
public class SellerOrderService {

    private static final Logger LOG = LoggerFactory.getLogger(SellerOrderService.class);

    static final String REJECTED_FOR_SHOPPER = "The seller could not fulfil this order.";

    private final MarketplaceSellerOrderRepository sellerOrders;
    private final MarketplaceOrderRepository orders;
    private final MarketplaceOrderLineRepository lines;
    private final MarketplaceSellerService sellers;
    private final MarketplaceCheckoutService checkout;
    private final MarketplacePaymentService payments;
    private final OrderService storeOrders;
    private final CatalogClient catalog;
    private final TradeClient trade;
    private final SellerAccess access;
    private final PlatformTransactionManager txManager;

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceOrderDTOs.SellerOrderView> mine(String status, Integer page, Integer size) {
        Long org = access.org();
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 50 : Math.min(size, 100));
        Page<MarketplaceSellerOrder> rows;
        if (status == null || status.isBlank()) {
            rows = sellerOrders.findBySellerOrganizationIdOrderByCreatedAtDesc(org, p);
        } else {
            String st = status.trim().toUpperCase(Locale.ROOT);
            try {
                SellerOrder.valueOf(st);
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Unknown order status: " + status);
            }
            rows = sellerOrders.findBySellerOrganizationIdAndAcceptanceStatusOrderByCreatedAtDesc(org, st, p);
        }
        // two queries for the whole page, never one per row
        List<Long> soIds = rows.getContent().stream().map(MarketplaceSellerOrder::getId).toList();
        Map<Long, List<MarketplaceOrderLine>> bySo = soIds.isEmpty() ? Map.of()
                : lines.findBySellerOrderIdIn(soIds).stream().collect(Collectors.groupingBy(MarketplaceOrderLine::getSellerOrderId));
        Map<Long, MarketplaceOrder> parents = orders.findAllById(
                rows.getContent().stream().map(MarketplaceSellerOrder::getMktOrderId).distinct().toList()).stream()
                .collect(Collectors.toMap(MarketplaceOrder::getId, Function.identity()));
        return PageResponse.of(rows, so -> view(so, parents.get(so.getMktOrderId()), bySo.getOrDefault(so.getId(), List.of())));
    }

    /** Not {@code @Transactional}: the remote release and sale sit between short transactions. */
    public MarketplaceOrderDTOs.SellerOrderView accept(Long id, MarketplaceOrderDTOs.AcceptRequest req) {
        MarketplaceSellerOrder so = own(id);
        if (SellerOrder.ACCEPTED.name().equals(so.getAcceptanceStatus())) return viewOf(so);      // a retried accept
        // The outcome before the version: a seller whose row went stale because the order EXPIRED needs that sentence,
        // not "someone else changed this" (found by the MKT-1e gate on a live stack).
        if (!SellerOrder.OFFERED.name().equals(so.getAcceptanceStatus()) || pastDeadline(so))
            throw new ValidationException(SellerOrder.EXPIRED.name().equals(so.getAcceptanceStatus()) || pastDeadline(so)
                    ? "This order expired before it was accepted." : "This order is " + label(so) + " and cannot be accepted.");
        stale(so, req == null ? null : req.version());
        sellers.assertActiveSeller();

        MarketplaceOrder order = orders.findById(so.getMktOrderId()).orElseThrow();
        List<MarketplaceOrderLine> ls = lines.findBySellerOrderIdOrderByIdAsc(so.getId());
        Map<Long, List<String>> serials = req == null || req.serials() == null ? Map.of() : req.serials();
        checkSerials(ls, serials);                                     // before anything changes

        release(so);
        OrderDTO sale;
        try {
            sale = storeOrders.placeMarketplace(new OrderService.MarketplaceSale(so.getSellerOrganizationId(),
                    "MKT-SO-" + so.getId(), order.getOrderNo(), order.getCustomerName(), order.getCustomerPhone(),
                    order.getDeliveryAddress() + ", " + order.getCity(),
                    ls.stream().map(l -> new OrderService.MarketplaceSaleLine(l.getSourceProductId(), l.getProductName(),
                            l.getQuantity(), l.getUnitPrice(), clean(serials.get(l.getId())))).toList(),
                    MarketplaceCheckoutService.CARD.equals(order.getPaymentMode()) ? "MARKETPLACE" : "COD"));
        } catch (RuntimeException saleFailure) {
            String why = DownstreamMessage.of(saleFailure);
            String rehold = checkout.hold(so, ls.get(0).getSourceProductId(), ls.get(0).getQuantity());
            if (rehold == null) {                                          // the flag must say what is true
                tx().executeWithoutResult(s -> sellerOrders.findById(so.getId()).ifPresent(f -> {
                    f.setHeld(true);
                    sellerOrders.save(f);
                }));
            }
            LOG.warn("MKT accept of {} failed at the sale ({}); re-hold {}", order.getOrderNo(), why,
                    rehold == null ? "taken" : "NOT taken: " + rehold);
            throw new ValidationException("The sale could not be recorded"
                    + (why == null ? ". Please try again." : ": " + why));
        }

        tx().executeWithoutResult(s -> {
            MarketplaceSellerOrder fresh = sellerOrders.findById(so.getId()).orElseThrow();
            MarketplaceCheckoutService.move(fresh, SellerOrder.ACCEPTED);
            fresh.setHeld(false);
            fresh.setStoreOrderId(sale.getId());
            fresh.setStoreOrderNo(sale.getOrderNo());
            fresh.setInvoiceNo(sale.getInvoiceNo());
            fresh.setDecidedByUserId(access.userId());
            fresh.setDecidedAt(LocalDateTime.now());
            sellerOrders.save(fresh);
            MarketplaceOrder parent = orders.findById(fresh.getMktOrderId()).orElseThrow();
            MarketplaceCheckoutService.rule(() -> com.myplus.marketplace.multiseller.domain.MarketplaceStateMachines.ORDER
                    .transition(Order.valueOf(parent.getStatus()), Order.CONFIRMED));
            parent.setStatus(Order.CONFIRMED.name());
            orders.save(parent);
        });
        return viewOf(sellerOrders.findById(so.getId()).orElseThrow());
    }

    public MarketplaceOrderDTOs.SellerOrderView reject(Long id, MarketplaceOrderDTOs.RejectRequest req) {
        MarketplaceSellerOrder so = own(id);
        if (SellerOrder.REJECTED.name().equals(so.getAcceptanceStatus())) return viewOf(so);
        if (!SellerOrder.OFFERED.name().equals(so.getAcceptanceStatus()))       // the outcome before the version
            throw new ValidationException("This order is " + label(so) + " and cannot be rejected.");
        stale(so, req == null ? null : req.version());
        String reason = req == null || req.reason() == null ? null : req.reason().trim();
        if (reason == null || reason.isEmpty())
            throw new ValidationException("Give a reason. MaxTheService support will see it.");
        if (reason.length() > 300) throw new ValidationException("Keep the reason under 300 characters.");

        tx().executeWithoutResult(s -> {
            MarketplaceSellerOrder fresh = sellerOrders.findById(so.getId()).orElseThrow();
            MarketplaceCheckoutService.move(fresh, SellerOrder.REJECTED);
            fresh.setRejectReason(reason);
            fresh.setDecidedByUserId(access.userId());
            fresh.setDecidedAt(LocalDateTime.now());
            sellerOrders.save(fresh);
            MarketplaceOrder parent = orders.findById(fresh.getMktOrderId()).orElseThrow();
            MarketplaceCheckoutService.cancel(parent, REJECTED_FOR_SHOPPER);
            orders.save(parent);
        });
        release(sellerOrders.findById(so.getId()).orElseThrow());     // after the commit: the stock goes back
        payments.refundIfCancelled(so.getMktOrderId());               // a card order's money goes back, once
        return viewOf(sellerOrders.findById(so.getId()).orElseThrow());
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────────

    /** Another seller's id reads exactly like one that does not exist. */
    private MarketplaceSellerOrder own(Long id) {
        if (id == null) throw new ResourceNotFoundException("No such order.");
        return sellerOrders.findByIdAndSellerOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("No such order."));
    }

    private static void stale(MarketplaceSellerOrder so, Integer version) {
        if (version != null && !Objects.equals(version, so.getVersion()))
            throw new OptimisticLockingFailureException("seller order changed");
    }

    private static boolean pastDeadline(MarketplaceSellerOrder so) {
        return so.getAcceptBy() != null && !LocalDateTime.now().isBefore(so.getAcceptBy());
    }

    private static String label(MarketplaceSellerOrder so) {
        return so.getAcceptanceStatus().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** A serial-tracked line needs exactly one serial per unit, named before the hold is let go. */
    void checkSerials(List<MarketplaceOrderLine> ls, Map<Long, List<String>> serials) {
        List<Long> ids = ls.stream().map(MarketplaceOrderLine::getSourceProductId).distinct().toList();
        List<ProductRef> refs = catalog.getProductsFresh(ids, true);         // as the seller (forwarded identity)
        Map<Long, ProductRef> byId = refs == null ? Map.of()
                : refs.stream().collect(Collectors.toMap(ProductRef::getId, Function.identity(), (a, b) -> a));
        for (MarketplaceOrderLine l : ls) {
            ProductRef ref = byId.get(l.getSourceProductId());
            if (ref == null || !Boolean.TRUE.equals(ref.getRequiresSerial())) continue;
            List<String> given = clean(serials.get(l.getId()));
            int need = l.getQuantity();
            if (given == null || given.size() != need)
                throw new ValidationException("Enter the serial number (IMEI) of each unit you are sending: " + need
                        + " for " + l.getProductName() + ".");
        }
    }

    static List<String> clean(List<String> in) {
        if (in == null) return null;
        List<String> out = new ArrayList<>();
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim());
        return out.isEmpty() ? null : out;
    }

    /** Best effort; the sweeper's held-row pass retries a release that failed. */
    void release(MarketplaceSellerOrder so) {
        try {
            AsOrg.run(so.getSellerOrganizationId(), () -> trade.releaseHold(so.getHoldKey()));
            if (Boolean.TRUE.equals(so.getHeld())) {
                tx().executeWithoutResult(s -> sellerOrders.findById(so.getId()).ifPresent(f -> {
                    f.setHeld(false);
                    sellerOrders.save(f);
                }));
            }
        } catch (RuntimeException e) {
            LOG.warn("MKT release of {} failed; the sweeper retries: {}", so.getHoldKey(), e.toString());
        }
    }

    private MarketplaceOrderDTOs.SellerOrderView viewOf(MarketplaceSellerOrder so) {
        return view(so, orders.findById(so.getMktOrderId()).orElse(null), lines.findBySellerOrderIdOrderByIdAsc(so.getId()));
    }

    static MarketplaceOrderDTOs.SellerOrderView view(MarketplaceSellerOrder so, MarketplaceOrder o, List<MarketplaceOrderLine> ls) {
        return new MarketplaceOrderDTOs.SellerOrderView(so.getId(), so.getVersion(), o == null ? null : o.getOrderNo(),
                so.getAcceptanceStatus(), so.getAcceptBy(), MarketplaceCheckoutService.secondsLeft(so),
                o == null ? null : o.getCustomerName(), o == null ? null : o.getCustomerPhone(),
                o == null ? null : o.getDeliveryAddress(), o == null ? null : o.getCity(), o == null ? null : o.getTotal(),
                so.getInvoiceNo(), so.getStoreOrderNo(), so.getRejectReason(), so.getCreatedAt(),
                ls.stream().map(l -> new MarketplaceOrderDTOs.SellerLineView(l.getId(), MarketplaceCheckoutService.lineView(l),
                        l.getSourceProductId(), l.getCommissionPolicyId(), l.getCommissionBasis(), l.getCommissionRate(),
                        l.getCommissionFixed(), l.getSettlementStatus())).toList(), so.getStoreOrderId(), o == null ? null : o.getPaymentMode(), so.getDeliveredAt());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }
}

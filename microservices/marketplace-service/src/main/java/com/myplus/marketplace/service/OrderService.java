package com.myplus.marketplace.service;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.commerce.contracts.dto.StockReturnLine;
import com.myplus.commerce.contracts.dto.StockReturnRequest;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.dto.OrderTrackDTO;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderItem;
import com.myplus.marketplace.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Order fulfilment (E1, slice 46). An order references the trade sale (invoiceNo); this tracks its fulfilment
 * lifecycle. org/user are passed in (controller reads CurrentUser) → unit-testable. Org-scoped.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);
    /** Synthetic actor for anonymous storefront reservations (org carries the tenant; stock is org-scoped). */
    private static final Long STOREFRONT_USER = 0L;

    private final OrderRepository repo;
    private final PaymentGateway paymentGateway;
    private final InventoryClient inventoryClient;   // O1: only the legacy (pre-invoice) cancel path still uses this
    private final com.myplus.commerce.contracts.client.TradeClient tradeClient;   // O1: the single revenue path
    private final NotificationService notificationService;
    private final com.myplus.marketplace.repository.OrderEventRepository orderEventRepository;
    private final com.myplus.marketplace.repository.StorefrontCustomerRepository customerRepo;
    private final CartService cartService;   // slice 68: close the persistent cart on successful checkout

    @Transactional
    public OrderDTO record(OrderDTO dto, Long orgId, Long userId) {
        Order o = Order.builder()
                .organizationId(orgId).userId(userId)
                .invoiceNo(dto.getInvoiceNo())
                .customerName(dto.getCustomerName())
                .total(dto.getTotal())
                .shippingAddress(dto.getShippingAddress())
                .source("POS").paymentMode(dto.getPaymentMode() != null ? dto.getPaymentMode() : "POS")
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .build();
        Order saved = repo.save(o);
        notificationService.notify(saved, "NEW", "Order received");   // slice 57: start the timeline
        return toDTO(saved);
    }

    /** Public guest order from the storefront (slice 47) — org comes from the request (no JWT identity). COD. */
    @Transactional
    public OrderDTO placePublic(OrderDTO dto) {
        if (dto.getOrganizationId() == null)
            throw new ValidationException("Store (organizationId) is required");
        if (dto.getCustomerName() == null || dto.getCustomerName().isBlank())
            throw new ValidationException("Your name is required");

        Long org = dto.getOrganizationId();

        // slice 61: link the order to the shopper's account when a valid session token is supplied.
        Long customerAccountId = null;
        if (dto.getCustomerToken() != null && !dto.getCustomerToken().isBlank()) {
            customerAccountId = customerRepo.findBySessionToken(dto.getCustomerToken().trim())
                    .filter(c -> org.equals(c.getOrganizationId()))   // only link within the same store
                    .map(com.myplus.marketplace.entity.StorefrontCustomer::getId).orElse(null);
        }

        // OMS O1: the order goes through business-service's sale path — the SAME one the till uses — which
        // reserves (FEFO), writes the invoice, confirms, applies tax, snapshots COGS, records the payment,
        // emits the GL event and audits, all idempotently. Marketplace no longer runs a reservation saga of its
        // own: before O1 it reserved and confirmed here and produced NO invoice, so every online sale was
        // missing from the P&L, trial balance, tax register and AR.
        boolean card = "CARD".equalsIgnoreCase(dto.getPaymentMode());
        // The cart token is a stable per-checkout handle, so it doubles as the idempotency key: a double-submit
        // of the same cart replays the SAME invoice instead of minting a second one. Falls back to a fresh key
        // for a cart-less (direct API) order, which then has nothing to deduplicate against.
        String idempotencyKey = (dto.getCartToken() != null && !dto.getCartToken().isBlank())
                ? "SF-" + dto.getCartToken() : UUID.randomUUID().toString();

        SaleRecordResult sale;
        try {
            sale = asStore(org, () -> tradeClient.recordSale(toSaleRequest(dto, org, idempotencyKey)));
        } catch (RuntimeException saleFailure) {
            // Out of stock (or any refusal) → nothing reserved, nothing invoiced and, critically, NOTHING
            // CHARGED: the card is only charged after the sale exists. This ordering is why an unavailable item
            // can never leave a shopper out of pocket.
            LOG.warn("Storefront order for org {} could not be recorded as a sale", org, saleFailure);
            throw new ValidationException("Sorry, an item in your cart is no longer available.");
        }
        if (sale == null || sale.getInvoiceNo() == null)
            throw new ValidationException("The order could not be completed. Please try again.");

        // Charge the SERVER's total, never the client's (OMS-5). dto.getTotal() is display-only from here on.
        String payStatus = "PENDING", payRef = null;
        BigDecimal charged = sale.getGrandTotal() != null ? sale.getGrandTotal() : dto.getTotal();
        if (card) {
            PaymentGateway.Charge ch = paymentGateway.charge(dto.getCardToken(), charged);
            if (!ch.success()) {
                // The sale exists, so a decline must REVERSE it — a void restores stock, refunds nothing (nothing
                // was paid) and nets Sales + AR back to zero. Leaving it would book revenue for an order the
                // shopper never paid for.
                reverseQuietly(org, sale.getInvoiceNo(), "Payment declined");
                throw new ValidationException("Payment declined: " + ch.declineReason());
            }
            payStatus = "PAID";
            payRef = ch.chargeId();
        }

        Order o = Order.builder()
                .organizationId(org)
                .invoiceNo(sale.getInvoiceNo())      // O1: the trade sale this order IS
                .booksStatus("POSTED")               // O1: it reached the books
                .customerName(dto.getCustomerName())
                .customerContact(dto.getCustomerContact())
                .total(charged)                      // the server's figure, not the client's
                .subTotal(dto.getSubTotal()).taxTotal(dto.getTaxTotal())
                .shippingFee(dto.getShippingFee()).shippingMethod(dto.getShippingMethod())
                .couponCode(dto.getCouponCode()).discountAmount(dto.getDiscountAmount())
                .shippingAddress(dto.getShippingAddress())
                .source("STOREFRONT").paymentMode(card ? "CARD" : "COD")
                .paymentStatus(payStatus).paymentRef(payRef)
                .customerAccountId(customerAccountId)
                .items(toItems(dto.getItems()))
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .build();
        Order saved;
        try {
            saved = repo.save(o);
        } catch (RuntimeException writeFailure) {
            // The sale is already in the books; if we cannot record our own order row, reverse it rather than
            // leave an invoice with no order behind it.
            reverseQuietly(org, sale.getInvoiceNo(), "Order could not be recorded");
            throw writeFailure;
        }

        notificationService.notify(saved, "NEW", "Order placed");   // slice 57: start the timeline
        cartService.markConverted(org, dto.getCartToken());          // slice 68: empty the persistent cart
        return toDTO(saved);
    }

    /** Build the sale request. Deliberately carries NO total — the server prices it (OMS-5). */
    private SaleRecordRequest toSaleRequest(OrderDTO dto, Long org, String idempotencyKey) {
        List<SaleRecordRequest.Line> lines = new ArrayList<>();
        if (dto.getItems() != null) {
            for (OrderDTO.Line l : dto.getItems()) {
                if (l.getProductId() == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
                lines.add(SaleRecordRequest.Line.builder()
                        .productId(l.getProductId())
                        .quantity(l.getQuantity().floatValue())
                        .unitPrice(l.getPrice())
                        .build());
            }
        }
        if (lines.isEmpty()) throw new ValidationException("Your cart is empty");

        // COD records no tender: the order becomes a receivable and settles on delivery, exactly as an unpaid
        // counter sale does. A CARD tender is added after the charge succeeds, by a follow-up slice — recording
        // it here would claim money we have not taken yet.
        return SaleRecordRequest.builder()
                .idempotencyKey(idempotencyKey)
                .organizationId(org)
                .channel("STOREFRONT")
                .customer(SaleRecordRequest.Customer.builder()
                        .name(dto.getCustomerName())
                        .contact(dto.getCustomerContact())
                        .address(dto.getShippingAddress())
                        .build())
                .lines(lines)
                .notes("Storefront order")
                .build();
    }

    /** Reverse a sale we just created, when the rest of checkout fails. Best-effort + logged: the alternative is
     *  leaving revenue booked for an order that never completed. */
    private void reverseQuietly(Long org, String invoiceNo, String reason) {
        if (invoiceNo == null) return;
        try {
            asStore(org, () -> { tradeClient.reverseSale(invoiceNo, reason); return null; });
        } catch (RuntimeException reversalFailure) {
            LOG.error("Storefront checkout failed AND the compensating void of invoice {} failed — the books now "
                    + "carry a sale for an order that was not completed; reconcile manually", invoiceNo, reversalFailure);
        }
    }

    // OMS O1: `reserveOrThrow` / `releaseQuietly` and `OrderSagaRecoveryRelay` are DELETED. Marketplace no longer
    // runs a reservation saga — reserve, confirm, release and the recovery re-drive all happen inside
    // business-service's sale path, which is the only place a sale is authored. Keeping a second copy here was
    // what let a storefront order decrement stock without ever producing an invoice.

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    /** Run an inventory call as the storefront tenant so X-Org-Id/X-User-Id are stamped on the outbound request
     *  (the order is anonymous — there is no inbound gateway identity to forward). */
    private <T> T asStore(Long org, Supplier<T> call) {
        AtomicReference<T> out = new AtomicReference<>();
        GatewayIdentityForwarding.runAs(STOREFRONT_USER, org, () -> out.set(call.get()));
        return out.get();
    }

    public List<OrderDTO> list(Long orgId, Long userId) {
        return repo.findScoped(orgId, userId).stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** A storefront shopper's own orders (slice 61, My Orders). */
    public List<OrderDTO> listForCustomer(Long customerAccountId) {
        return repo.findByCustomerAccountIdOrderByCreatedAtDesc(customerAccountId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Public order tracking (slice 56): a guest looks up their order by id + contact. Returns only on a contact
     *  match (case-insensitive, non-blank) so order existence isn't revealed; minimal projection. */
    public com.myplus.marketplace.dto.OrderTrackDTO trackPublic(Long ref, String contact) {
        Order o = (ref == null) ? null : repo.findById(ref).orElse(null);
        String c = contact == null ? "" : contact.trim();
        if (o == null || c.isEmpty() || o.getCustomerContact() == null
                || !o.getCustomerContact().trim().equalsIgnoreCase(c)) {
            throw new ResourceNotFoundException("No order found for that reference and contact.");
        }
        java.util.List<com.myplus.marketplace.dto.OrderTrackDTO.Event> timeline = new ArrayList<>();
        for (com.myplus.marketplace.entity.OrderEvent e : orderEventRepository.findByOrderIdOrderByCreatedAtAsc(o.getId())) {
            timeline.add(new com.myplus.marketplace.dto.OrderTrackDTO.Event(e.getStatus(), e.getCreatedAt()));
        }
        return new com.myplus.marketplace.dto.OrderTrackDTO(
                o.getId(), o.getCustomerName(),
                o.getFulfilmentStatus() != null ? o.getFulfilmentStatus().name() : null,
                o.getCreatedAt(), o.getTotal(), timeline);
    }

    public OrderDTO get(Long id, Long orgId, Long userId) {
        return toDTO(repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found")));
    }

    @Transactional
    public OrderDTO updateStatus(Long id, String status, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        FulfilmentStatus s;
        try { s = FulfilmentStatus.valueOf(status == null ? "" : status.trim().toUpperCase()); }
        catch (Exception e) { throw new ValidationException("Invalid status: " + status); }

        // E7 cancel (slice 51): transitioning INTO CANCELLED reverses the order. Idempotent — only on the FIRST
        // transition, so re-cancelling never reverses twice.
        //
        // O1 changed what "has something to reverse" means. This used to require `reservationId != null`, because
        // a marketplace-held inventory reservation was the only thing a cancel could undo. Now the storefront
        // records a SALE and holds no reservation of its own, so that guard silently skipped every new order —
        // stock stayed decremented and the revenue stayed booked. The question is "is there anything to reverse?":
        // an INVOICE (post-O1) or a RESERVATION (pre-O1 orders, which still exist in live data).
        boolean nowCancelling = s == FulfilmentStatus.CANCELLED && o.getFulfilmentStatus() != FulfilmentStatus.CANCELLED;
        boolean hasSomethingToReverse = hasText(o.getInvoiceNo()) || o.getReservationId() != null;
        if (nowCancelling && hasSomethingToReverse && !o.getItems().isEmpty()) {
            returnStockQuietly(o);
        }

        o.setFulfilmentStatus(s);
        Order saved = repo.save(o);
        notificationService.notify(saved, s.name(), "Status updated to " + s.name());   // slice 57: timeline event
        return toDTO(saved);
    }

    /** Refund a card-paid order (E6, slice 70), full or partial, via the payment provider. Caps at the remaining
     *  refundable amount; flips paymentStatus to PARTIALLY_REFUNDED / REFUNDED. Org-scoped (anti-IDOR). */
    @Transactional
    public OrderDTO refund(Long id, BigDecimal amount, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!isCardRefundable(o))
            throw new ValidationException("Only a card-paid order can be refunded (COD is settled in cash)");
        if (!doRefund(o, amount))
            throw new ValidationException("Order is already fully refunded");
        Order saved = repo.save(o);
        notificationService.notify(saved, "REFUNDED", "Refund issued");   // timeline (slice 57)
        return toDTO(saved);
    }

    /** True when the order was paid by card and still has refundable balance left. */
    private boolean isCardRefundable(Order o) {
        return ("PAID".equalsIgnoreCase(o.getPaymentStatus()) || "PARTIALLY_REFUNDED".equalsIgnoreCase(o.getPaymentStatus()))
                && o.getPaymentRef() != null;
    }

    /** Apply a refund to a card-refundable order (caller checked {@link #isCardRefundable}); caps at the remaining
     *  amount, calls the gateway, updates amount + status. Returns false if nothing was left to refund. */
    private boolean doRefund(Order o, BigDecimal amount) {
        BigDecimal total = o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO;
        BigDecimal already = o.getRefundedAmount() != null ? o.getRefundedAmount() : BigDecimal.ZERO;
        BigDecimal remaining = total.subtract(already);
        if (remaining.signum() <= 0) return false;
        BigDecimal amt = (amount == null || amount.signum() <= 0) ? remaining : amount.min(remaining);
        PaymentGateway.Refund r = paymentGateway.refund(o.getPaymentRef(), amt);
        if (!r.success()) throw new ValidationException("Refund failed: " + (r.reason() != null ? r.reason() : "unknown"));
        BigDecimal newRefunded = already.add(amt);
        o.setRefundedAmount(newRefunded);
        o.setRefundRef(r.refundId());
        o.setPaymentStatus(newRefunded.compareTo(total) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED");
        return true;
    }

    /** Shopper requests a return (E10, slice 71) — public, verified by order id + contact (slice 56 pattern). Only a
     *  DELIVERED order is returnable. Sets RETURN_REQUESTED + reason for the back-office to process. */
    @Transactional
    public OrderTrackDTO requestReturn(Long ref, String contact, String reason) {
        Order o = (ref == null) ? null : repo.findById(ref).orElse(null);
        String c = contact == null ? "" : contact.trim();
        if (o == null || c.isEmpty() || o.getCustomerContact() == null
                || !o.getCustomerContact().trim().equalsIgnoreCase(c)) {
            throw new ResourceNotFoundException("No order found for that reference and contact.");
        }
        if (o.getFulfilmentStatus() != FulfilmentStatus.DELIVERED)
            throw new ValidationException("Only a delivered order can be returned");
        o.setFulfilmentStatus(FulfilmentStatus.RETURN_REQUESTED);
        o.setReturnReason(reason);
        Order saved = repo.save(o);
        notificationService.notify(saved, "RETURN_REQUESTED", "Return requested");
        return trackPublic(saved.getId(), c);
    }

    /** Back-office processes a return (E10, slice 71): return stock to inventory (G2 inverse saga) + refund a card
     *  order (best-effort) → RETURNED. From RETURN_REQUESTED or DELIVERED (admin-initiated). Org-scoped. */
    @Transactional
    public OrderDTO processReturn(Long id, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        FulfilmentStatus s = o.getFulfilmentStatus();
        if (s != FulfilmentStatus.RETURN_REQUESTED && s != FulfilmentStatus.DELIVERED)
            throw new ValidationException("Only a delivered / return-requested order can be returned");

        // Same O1 correction as the cancel path: an invoice OR a legacy reservation means there is something to
        // reverse. A returned order that only checked reservationId would silently keep the revenue booked.
        if ((hasText(o.getInvoiceNo()) || o.getReservationId() != null) && !o.getItems().isEmpty()) {
            returnStockQuietly(o);                          // stock back (+ books, post-O1)
        }
        if (isCardRefundable(o)) {
            try { doRefund(o, null); }                       // full remaining refund, best-effort
            catch (RuntimeException refundFailure) {
                LOG.warn("Return {} processed (stock returned) but refund failed; reconcile manually", o.getId(), refundFailure);
            }
        }
        o.setFulfilmentStatus(FulfilmentStatus.RETURNED);
        Order saved = repo.save(o);
        notificationService.notify(saved, "RETURNED", "Return processed");   // timeline (slice 57)
        return toDTO(saved);
    }

    /**
     * Reverse a cancelled order.
     *
     * <p><b>OMS O1 changed what this means.</b> Before O1 a storefront order had no invoice, so returning stock
     * was the whole reversal. Now it has one, and returning stock alone would leave the revenue booked —
     * overstating P&amp;L and the tax register, which is the same defect O1 exists to remove, pointing the other
     * way. So a POSTED order is reversed by VOIDING its invoice, which restores the stock, refunds whatever was
     * paid, zeroes the invoice in place and posts the GL reversal — one operation, business-service's own.
     *
     * <p>A pre-fulfilment cancellation is a void rather than a credit note: nothing shipped, so there is nothing
     * to credit back.
     *
     * <p>Orders placed BEFORE O1 (`LEGACY_UNPOSTED`, no invoice) still take the old stock-only path — they have
     * no sale to reverse.
     *
     * <p>Best-effort + logged either way: a failure leaves the order cancelled rather than blocking the
     * cancellation, but a failed void is logged at ERROR because the books are then wrong until someone looks.
     */
    private void returnStockQuietly(Order o) {
        if (o.getInvoiceNo() != null && !o.getInvoiceNo().isBlank()) {
            try {
                asStore(o.getOrganizationId(),
                        () -> { tradeClient.reverseSale(o.getInvoiceNo(), "Order cancelled"); return null; });
                o.setBooksStatus("REVERSED");
            } catch (RuntimeException reversalFailure) {
                LOG.error("Order {} cancelled but voiding invoice {} failed — stock is NOT back and the revenue "
                        + "is still booked; reconcile manually", o.getId(), o.getInvoiceNo(), reversalFailure);
            }
            return;
        }

        // Pre-O1 order: no invoice exists, so inventory is the only thing to put back.
        List<StockReturnLine> lines = new ArrayList<>();
        for (OrderItem it : o.getItems()) {
            if (it.getProductId() == null || it.getQuantity() == null || it.getQuantity() <= 0) continue;
            lines.add(new StockReturnLine(it.getProductId(), it.getQuantity().floatValue()));
        }
        if (lines.isEmpty()) return;
        try {
            inventoryClient.returnStock(o.getReservationId(), new StockReturnRequest(lines));
        } catch (RuntimeException returnFailure) {
            LOG.warn("Order {} cancelled but stock-return for reservation {} failed; reconcile manually",
                    o.getId(), o.getReservationId(), returnFailure);
        }
    }

    private List<OrderItem> toItems(List<OrderDTO.Line> lines) {
        List<OrderItem> items = new ArrayList<>();
        if (lines == null) return items;
        for (OrderDTO.Line l : lines) {
            if (l.getProductId() == null) continue;
            items.add(OrderItem.builder()
                    .productId(l.getProductId()).quantity(l.getQuantity()).price(l.getPrice()).build());
        }
        return items;
    }

    private OrderDTO toDTO(Order o) {
        OrderDTO d = new OrderDTO();
        d.setId(o.getId());
        d.setOrganizationId(o.getOrganizationId());
        d.setInvoiceNo(o.getInvoiceNo());
        d.setCustomerName(o.getCustomerName());
        d.setCustomerContact(o.getCustomerContact());
        d.setTotal(o.getTotal());
        d.setSubTotal(o.getSubTotal());
        d.setTaxTotal(o.getTaxTotal());
        d.setShippingFee(o.getShippingFee());
        d.setShippingMethod(o.getShippingMethod());
        d.setCouponCode(o.getCouponCode());
        d.setDiscountAmount(o.getDiscountAmount());
        d.setFulfilmentStatus(o.getFulfilmentStatus() != null ? o.getFulfilmentStatus().name() : null);
        d.setSource(o.getSource());
        d.setPaymentMode(o.getPaymentMode());
        d.setPaymentStatus(o.getPaymentStatus());
        d.setPaymentRef(o.getPaymentRef());
        d.setRefundRef(o.getRefundRef());
        d.setRefundedAmount(o.getRefundedAmount());
        d.setReservationId(o.getReservationId());
        d.setReservationStatus(o.getReservationStatus());
        d.setShippingAddress(o.getShippingAddress());
        d.setReturnReason(o.getReturnReason());
        d.setCreatedAt(o.getCreatedAt());
        return d;
    }
}

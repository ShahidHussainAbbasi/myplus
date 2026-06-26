package com.myplus.marketplace.service;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final InventoryClient inventoryClient;

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
        return toDTO(repo.save(o));
    }

    /** Public guest order from the storefront (slice 47) — org comes from the request (no JWT identity). COD. */
    @Transactional
    public OrderDTO placePublic(OrderDTO dto) {
        if (dto.getOrganizationId() == null)
            throw new ValidationException("Store (organizationId) is required");
        if (dto.getCustomerName() == null || dto.getCustomerName().isBlank())
            throw new ValidationException("Your name is required");

        Long org = dto.getOrganizationId();

        // E7 (slice 49): reserve stock via the SAME inventory saga POS uses. OUT_OF_STOCK blocks the order
        // (nothing held, no charge). Reserve before charging so a paid order is always fulfillable.
        String reservationId = reserveOrThrow(dto, org);

        // E2b: Card → sandbox charge (decline releases the hold + blocks the order); COD → pay-on-delivery (PENDING).
        boolean card = "CARD".equalsIgnoreCase(dto.getPaymentMode());
        String payStatus = "PENDING", payRef = null;
        if (card) {
            PaymentGateway.Charge ch = paymentGateway.charge(dto.getCardToken(), dto.getTotal());
            if (!ch.success()) {
                releaseQuietly(reservationId, org);
                throw new ValidationException("Payment declined: " + ch.declineReason());
            }
            payStatus = "PAID";
            payRef = ch.chargeId();
        }

        Order o = Order.builder()
                .organizationId(org)
                .customerName(dto.getCustomerName())
                .customerContact(dto.getCustomerContact())
                .total(dto.getTotal())
                .shippingAddress(dto.getShippingAddress())
                .source("STOREFRONT").paymentMode(card ? "CARD" : "COD")
                .paymentStatus(payStatus).paymentRef(payRef)
                .reservationId(reservationId)
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .build();
        Order saved;
        try {
            saved = repo.save(o);
        } catch (RuntimeException writeFailure) {     // compensate: release the hold, then abort
            releaseQuietly(reservationId, org);
            throw writeFailure;
        }

        // Confirm the hold → stock is decremented. Best-effort: a confirm failure leaves the hold (manual
        // reconcile) — marketplace has no recovery relay (unlike POS, U3c). The order is already recorded.
        if (reservationId != null) {
            try {
                asStore(org, () -> inventoryClient.confirm(reservationId));
            } catch (RuntimeException confirmFailure) {
                LOG.warn("Storefront order {} placed but reservation {} confirm failed; held for reconcile",
                        saved.getId(), reservationId, confirmFailure);
            }
        }
        return toDTO(saved);
    }

    /** Reserve the cart's stock (FEFO) for the store's org; OUT_OF_STOCK / empty cart → ValidationException.
     *  Returns the reservationId, or null when the order has no line items (nothing to reserve). */
    private String reserveOrThrow(OrderDTO dto, Long org) {
        List<OrderDTO.Line> items = dto.getItems();
        if (items == null || items.isEmpty()) return null;   // nothing to reserve (defensive)

        List<StockReservationLine> lines = new ArrayList<>();
        for (OrderDTO.Line l : items) {
            if (l.getProductId() == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
            lines.add(new StockReservationLine(l.getProductId(), BigDecimal.valueOf(l.getQuantity())));
        }
        if (lines.isEmpty()) return null;

        StockReservationRequest req = new StockReservationRequest(UUID.randomUUID().toString(), lines);
        StockReservationResponse resp = asStore(org, () -> inventoryClient.reserve(req));
        if (resp == null || resp.getStatus() != ReservationStatus.RESERVED) {
            String why = (resp != null && resp.getMessage() != null) ? ": " + resp.getMessage() : "";
            throw new ValidationException("Sorry, an item in your cart is out of stock" + why);
        }
        return resp.getReservationId();
    }

    private void releaseQuietly(String reservationId, Long org) {
        if (reservationId == null) return;
        try {
            asStore(org, () -> inventoryClient.release(reservationId));
        } catch (RuntimeException ignore) {
            LOG.warn("Compensating release failed for reservation {} (hold will lapse/cleanup later)", reservationId);
        }
    }

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
        o.setFulfilmentStatus(s);
        return toDTO(repo.save(o));
    }

    private OrderDTO toDTO(Order o) {
        OrderDTO d = new OrderDTO();
        d.setId(o.getId());
        d.setOrganizationId(o.getOrganizationId());
        d.setInvoiceNo(o.getInvoiceNo());
        d.setCustomerName(o.getCustomerName());
        d.setCustomerContact(o.getCustomerContact());
        d.setTotal(o.getTotal());
        d.setFulfilmentStatus(o.getFulfilmentStatus() != null ? o.getFulfilmentStatus().name() : null);
        d.setSource(o.getSource());
        d.setPaymentMode(o.getPaymentMode());
        d.setPaymentStatus(o.getPaymentStatus());
        d.setPaymentRef(o.getPaymentRef());
        d.setReservationId(o.getReservationId());
        d.setShippingAddress(o.getShippingAddress());
        d.setCreatedAt(o.getCreatedAt());
        return d;
    }
}

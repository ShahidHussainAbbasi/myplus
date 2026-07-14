package com.myplus.business_service.service;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The local DB writes of the sell saga (slice 33, U3b), in their own committed transactions
 * ({@code REQUIRES_NEW}) so the PENDING sale is durable BEFORE the orchestrator calls {@code confirm} on
 * inventory — and is independent of the caller's (legacy) transaction. Separate bean so the proxy applies
 * (no self-invocation).
 */
@Service
@RequiredArgsConstructor
public class SagaSaleWriter {

    private final ICustomerService customerService;
    private final ICustomerHistoryService customerHistoryService;
    private final ISellService sellService;
    private final PaymentService paymentService;
    private final com.myplus.business_service.repository.CashierShiftRepo cashierShiftRepo;

    private static java.math.BigDecimal nz(java.math.BigDecimal v) { return v != null ? v : java.math.BigDecimal.ZERO; }

    /** Write Customer + invoice (PENDING, carrying the reservation) + Sell lines (productId, catalog rate). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CustomerHistory writePending(CustomerHistoryDTO dto, String reservationId, String idempotencyKey,
                                        AuthenticatedUser user, List<SagaLine> lines) {
        dto.setUserId(user.getUserId());
        Customer customer;
        try {
            customer = customerService.saveUpdateCustomer(dto);   // declares checked Exception
        } catch (Exception e) {
            throw new RuntimeException("Failed to save customer for sale", e);
        }
        customerService.save(customer);

        CustomerHistory ch = customerHistoryService.saveUpdateCustomerHistory(dto);
        ch.setCustomer(customer);
        ch.setReservationId(reservationId);
        ch.setIdempotencyKey(idempotencyKey);
        ch.setSagaStatus("PENDING");
        // POS day-close (slice 39): stamp the cashier's open shift (if any) so the X/Z report can aggregate it.
        cashierShiftRepo.findFirstByOrganizationIdAndUserIdAndStatusOrderByOpenedAtDesc(
                user.getOrganizationId(), user.getUserId(), com.myplus.business_service.entity.ShiftStatus.OPEN)
                .ifPresent(shift -> ch.setShiftId(shift.getId()));
        // SF-1/SF-2: totals + settle + Sell lines + recomputeDue, via the ONE shared path (also used by updateSell).
        applyInvoice(ch, lines, dto, user, false);
        return ch;
    }

    /**
     * SF-1/SF-2 — THE authoritative invoice apply, shared by new sales (writePending) and edits (updateSell):
     * recompute the tax summary (subTotal/taxTotal/grandTotal) from the authoritative lines, settle payment,
     * (re)write the Sell lines, and recompute the customer's running due. Runs in the caller's transaction.
     * <p>Settlement: {@code paid = existingPaid (edit only) + any new tender}, and {@code due = paid − grandTotal}
     * (negative while owing) — the server DERIVES due; the client-sent dueAmount is not trusted. For an edit the
     * cashier only adds NEW payment (the prior payment is kept), so it is never double-counted.
     */
    public void applyInvoice(CustomerHistory ch, List<SagaLine> lines, CustomerHistoryDTO dto,
                             AuthenticatedUser user, boolean replaceLines) {
        java.math.BigDecimal subTotal = java.math.BigDecimal.ZERO, taxTotal = java.math.BigDecimal.ZERO,
                grandTotal = java.math.BigDecimal.ZERO;
        for (SagaLine l : lines) {
            java.math.BigDecimal lineTax = nz(l.taxAmount());
            java.math.BigDecimal lineGross = nz(l.lineGross());
            subTotal = subTotal.add(lineGross.subtract(lineTax));
            taxTotal = taxTotal.add(lineTax);
            grandTotal = grandTotal.add(lineGross);
        }
        ch.setSubTotal(subTotal);
        ch.setTaxTotal(taxTotal);
        ch.setGrandTotal(grandTotal);
        // Stamp the store on a NEW invoice only; an edit keeps the store it was raised at, even if the editor's
        // active store differs (re-homing a sale to another store would silently move the money between them).
        if (!replaceLines && ch.getStoreId() == null) ch.setStoreId(user.getActiveLocationId());

        // Settle: an edit KEEPS the invoice's prior payment and ADDS any new tender; a new sale starts at 0.
        java.math.BigDecimal existingPaid = replaceLines ? nz(ch.getPaidAmount()) : java.math.BigDecimal.ZERO;
        boolean hasTenders = dto.getTenders() != null && !dto.getTenders().isEmpty();
        java.math.BigDecimal paid = existingPaid;
        if (hasTenders) {
            // Settle the NEW tender against what's still owed (grandTotal − alreadyPaid), so an edit's extra
            // payment is capped at the remaining balance and never double-counts the prior payment. For a new
            // sale existingPaid = 0, so this is simply the whole grand total.
            java.math.BigDecimal remaining = grandTotal.subtract(existingPaid);
            if (remaining.compareTo(java.math.BigDecimal.ZERO) < 0) remaining = java.math.BigDecimal.ZERO;
            SettleResult st = PaymentService.settle(remaining, dto.getTenders());
            ch.setPaymentMode(st.paymentMode());
            ch.setTenderedAmount(st.tendered());
            ch.setChangeAmount(st.change());
            paid = existingPaid.add(st.paid());
        }
        ch.setPaidAmount(paid);
        ch.setDueAmount(paid.subtract(grandTotal));   // negative while owing (recomputeDue convention)
        customerHistoryService.save(ch);

        if (hasTenders) {
            paymentService.record(ch.getCustomer_history_id(), dto.getTenders(),
                    user.getOrganizationId(), user.getUserId());
        }

        // (Re)write the Sell lines authoritatively (discount + catalog snapshot + tax + sold rate).
        if (replaceLines) {
            for (Sell o : sellService.findByInvoiceScoped(ch.getCustomer_history_id(),
                    user.getOrganizationId(), user.getUserId())) {
                sellService.deleteById(o.getSellId());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (SagaLine l : lines) {
            Sell sell = new Sell();
            sell.setUserId(user.getUserId());
            sell.setOrganizationId(user.getOrganizationId());
            sell.setStoreId(user.getActiveLocationId());   // multi-location: the store this sale happened at (null = single-store)
            sell.setProductId(l.productId());        // saga sell: catalog product, no local Stock FK
            sell.setQuantity(l.quantity());
            sell.setSellRate(l.sellRate());          // the ACTUAL sold rate (cashier's rate; falls back to catalog)
            sell.setCatalogPrice(l.catalogPrice());  // catalog master price snapshot at sale time (for reports)
            sell.setCostPrice(l.costPrice());        // SF-10: unit COGS snapshot → per-line margin in the report
            sell.setDiscount(l.discount());
            sell.setDt(l.discountType());            // persist the discount type ("%" / "Amount") for the sell history table
            sell.setTotalAmount(l.totalAmount());
            sell.setNetAmount(l.netAmount());
            sell.setSrp(l.srp());
            sell.setTaxRate(l.taxRate());          // G3: applied tax rate + amount per line
            sell.setTaxAmount(l.taxAmount());
            sell.setCustomerHistory(ch);
            sell.setDated(now);
            sell.setUpdated(now);
            sellService.save(sell);
        }
        customerService.recomputeDue(ch.getCustomer());
    }

    /** Flip the invoice's saga status (PENDING → CONFIRMED/FAILED) in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStatus(Long customerHistoryId, String status) {
        customerHistoryService.findById(customerHistoryId).ifPresent(ch -> {
            ch.setSagaStatus(status);
            customerHistoryService.save(ch);
        });
    }
}

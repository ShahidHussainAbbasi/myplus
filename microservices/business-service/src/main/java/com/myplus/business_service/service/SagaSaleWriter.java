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
        // G3 (slice 35): invoice tax summary for the receipt + tax report (subTotal = Σ net, etc.).
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

        // G5 (slice 37): settle the tenders against the (tax-inclusive) grand total. Only when tenders are sent —
        // otherwise leave the legacy paid/due the cart already set (backward-compatible). dueAmount = paid − bill
        // (negative while owing), matching the existing convention recomputeDue() relies on.
        boolean hasTenders = dto.getTenders() != null && !dto.getTenders().isEmpty();
        if (hasTenders) {
            SettleResult st = PaymentService.settle(grandTotal, dto.getTenders());
            ch.setPaymentMode(st.paymentMode());
            ch.setTenderedAmount(st.tendered());
            ch.setChangeAmount(st.change());
            ch.setPaidAmount(st.paid());
            ch.setDueAmount(st.paid().subtract(grandTotal));
        }
        customerHistoryService.save(ch);

        if (hasTenders) {
            paymentService.record(ch.getCustomer_history_id(), dto.getTenders(),
                    user.getOrganizationId(), user.getUserId());
        }
        customerService.recomputeDue(customer);

        LocalDateTime now = LocalDateTime.now();
        for (SagaLine l : lines) {
            Sell sell = new Sell();
            sell.setUserId(user.getUserId());
            sell.setOrganizationId(user.getOrganizationId());
            sell.setProductId(l.productId());        // saga sell: catalog product, no local Stock FK
            sell.setQuantity(l.quantity());
            sell.setSellRate(l.sellRate());          // catalog list price (D1)
            sell.setDiscount(l.discount());
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
        return ch;
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

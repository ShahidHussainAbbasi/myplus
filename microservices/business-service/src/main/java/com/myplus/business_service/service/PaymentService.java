package com.myplus.business_service.service;

import com.myplus.business_service.dto.TenderDTO;
import com.myplus.business_service.entity.Payment;
import com.myplus.business_service.entity.PaymentMethod;
import com.myplus.business_service.repository.PaymentRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Payments / tender (G5, slice 37). {@link #settle} is pure (unit-testable): given the invoice grand total (G3,
 * tax-inclusive) and the tenders, it computes paid (non-credit), due, cash change, total tendered and the summary
 * mode. {@link #record} persists the tenders; {@link #refund} writes a REFUND tender for a sale return.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    static final int SCALE = 2;

    private final PaymentRepo paymentRepo;

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static BigDecimal scale(BigDecimal v) { return nz(v).setScale(SCALE, RoundingMode.HALF_UP); }

    static PaymentMethod parse(String s) {
        try { return PaymentMethod.valueOf(s == null ? "CASH" : s.trim().toUpperCase()); }
        catch (Exception e) { return PaymentMethod.CASH; }
    }

    /** Settle the sale: paid = Σ non-credit tenders; due = max(0, grandTotal − paid); change = overpayment. */
    public static SettleResult settle(BigDecimal grandTotal, List<TenderDTO> tenders) {
        BigDecimal amountDue = scale(grandTotal);
        BigDecimal paid = BigDecimal.ZERO, tendered = BigDecimal.ZERO;
        if (tenders != null) {
            for (TenderDTO t : tenders) {
                BigDecimal amt = nz(t.getAmount());
                if (amt.signum() == 0) continue;
                tendered = tendered.add(amt);
                if (parse(t.getMethod()) != PaymentMethod.CREDIT) paid = paid.add(amt);
            }
        }
        paid = scale(paid);
        tendered = scale(tendered);
        BigDecimal due = scale(amountDue.subtract(paid).max(BigDecimal.ZERO));
        BigDecimal change = scale(paid.subtract(amountDue).max(BigDecimal.ZERO));
        return new SettleResult(paid, due, change, tendered, mode(tenders));
    }

    /** Summary mode: single method's name, SPLIT for several, or null when nothing was tendered. */
    static String mode(List<TenderDTO> tenders) {
        if (tenders == null) return null;
        // CREDIT (on account) counts even at zero tendered; other methods only when an amount was paid.
        List<PaymentMethod> methods = tenders.stream()
                .filter(t -> parse(t.getMethod()) == PaymentMethod.CREDIT || nz(t.getAmount()).signum() != 0)
                .map(t -> parse(t.getMethod())).distinct().toList();
        if (methods.isEmpty()) return null;
        return methods.size() == 1 ? methods.get(0).name() : "SPLIT";
    }

    /** Persist each non-zero tender against the invoice. */
    @Transactional
    public void record(Long customerHistoryId, List<TenderDTO> tenders, Long orgId, Long userId) {
        if (tenders == null) return;
        for (TenderDTO t : tenders) {
            if (nz(t.getAmount()).signum() == 0) continue;
            paymentRepo.save(Payment.builder()
                    .customerHistoryId(customerHistoryId)
                    .method(parse(t.getMethod()))
                    .amount(scale(t.getAmount()))
                    .reference(t.getReference())
                    .organizationId(orgId).userId(userId)
                    .build());
        }
    }

    /** Record money returned to the customer on a sale return (a negative REFUND tender). */
    @Transactional
    public Payment refund(Long customerHistoryId, BigDecimal amount, Long orgId, Long userId) {
        return paymentRepo.save(Payment.builder()
                .customerHistoryId(customerHistoryId)
                .method(PaymentMethod.REFUND)
                .amount(scale(nz(amount).abs().negate()))
                .organizationId(orgId).userId(userId)
                .build());
    }

    public List<Payment> forInvoice(Long customerHistoryId) {
        return paymentRepo.findByCustomerHistoryId(customerHistoryId);
    }
}

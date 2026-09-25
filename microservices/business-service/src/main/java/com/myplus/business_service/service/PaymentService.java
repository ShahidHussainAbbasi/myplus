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
    private final com.myplus.business_service.util.RequestUtil requestUtil;   // multi-location: the active store

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static BigDecimal scale(BigDecimal v) { return nz(v).setScale(SCALE, RoundingMode.HALF_UP); }

    static PaymentMethod parse(String s) {
        try { return PaymentMethod.valueOf(s == null ? "CASH" : s.trim().toUpperCase()); }
        catch (Exception e) { return PaymentMethod.CASH; }
    }

    /**
     * Settle the sale against what is still owed.
     *
     * <p>PAID-1 — {@code paid} is what the shop KEEPS: {@code min(Σ non-credit tenders, amountDue)}. It used to be
     * the whole tender, so a customer who handed over 272.00 for a 42.00 bill was recorded as having paid 272.00
     * (INV-000054): the invoice showed due +230, voiding it refunded 272, a return refunded the change again, the
     * customer's other debt shrank by 230 and the shift expected 230 more cash than the drawer held. The change is
     * still reported ({@code change}) — it is a fact about the counter, printed on the receipt — it is just not money
     * the shop received.
     */
    public static SettleResult settle(BigDecimal grandTotal, List<TenderDTO> tenders) {
        BigDecimal amountDue = scale(grandTotal);
        BigDecimal handedOver = BigDecimal.ZERO, tendered = BigDecimal.ZERO;
        if (tenders != null) {
            for (TenderDTO t : tenders) {
                BigDecimal amt = nz(t.getAmount());
                if (amt.signum() == 0) continue;
                tendered = tendered.add(amt);
                if (parse(t.getMethod()) != PaymentMethod.CREDIT) handedOver = handedOver.add(amt);
            }
        }
        handedOver = scale(handedOver);
        tendered = scale(tendered);
        BigDecimal paid = handedOver.min(amountDue.max(BigDecimal.ZERO));          // what the shop keeps
        BigDecimal due = scale(amountDue.subtract(paid).max(BigDecimal.ZERO));
        BigDecimal change = scale(handedOver.subtract(amountDue).max(BigDecimal.ZERO));   // what went back
        return new SettleResult(scale(paid), due, change, tendered, mode(tenders));
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

    /** Marks the payment row that records change handed back — see {@link #record(Long, List, Long, Long, BigDecimal)}. */
    public static final String CHANGE_REFERENCE = "CHANGE";

    /** Persist each non-zero tender against the invoice (no change handed back). */
    @Transactional
    public void record(Long customerHistoryId, List<TenderDTO> tenders, Long orgId, Long userId) {
        record(customerHistoryId, tenders, orgId, userId, BigDecimal.ZERO);
    }

    /**
     * Persist each tender AS HANDED OVER, plus — when there was change — ONE row for the cash that went back:
     * {@code CASH −change, reference "CHANGE"}. The Odoo POS model: the audit trail shows cash in AND cash out, and
     * every reader that sums a method (the shift's expected cash) nets to what the drawer actually kept. Change is
     * always cash out of the drawer, whatever the tender (a card is charged exactly).
     */
    @Transactional
    public void record(Long customerHistoryId, List<TenderDTO> tenders, Long orgId, Long userId, BigDecimal change) {
        recordTenders(customerHistoryId, tenders, orgId, userId);
        if (change != null && change.signum() > 0) {
            paymentRepo.save(Payment.builder()
                    .customerHistoryId(customerHistoryId)
                    .method(PaymentMethod.CASH)
                    .amount(scale(change).negate())
                    .reference(CHANGE_REFERENCE)
                    .organizationId(orgId).userId(userId)
                    .storeId(requestUtil.activeStoreId())
                    .build());
        }
    }

    private void recordTenders(Long customerHistoryId, List<TenderDTO> tenders, Long orgId, Long userId) {
        if (tenders == null) return;
        for (TenderDTO t : tenders) {
            if (nz(t.getAmount()).signum() == 0) continue;
            paymentRepo.save(Payment.builder()
                    .customerHistoryId(customerHistoryId)
                    .method(parse(t.getMethod()))
                    .amount(scale(t.getAmount()))
                    .reference(t.getReference())
                    .organizationId(orgId).userId(userId)
                    .storeId(requestUtil.activeStoreId())   // the store that took the tender
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
                .storeId(requestUtil.activeStoreId())       // the store that handed the money back
                .build());
    }

    public List<Payment> forInvoice(Long customerHistoryId) {
        return paymentRepo.findByCustomerHistoryId(customerHistoryId);
    }
}

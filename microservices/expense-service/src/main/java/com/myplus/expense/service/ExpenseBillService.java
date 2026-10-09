package com.myplus.expense.service;

import com.myplus.common.security.time.TenantClock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentAllocationRef;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.commerce.contracts.dto.PaymentView;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.dto.ExpenseDtos.BillPaymentView;
import com.myplus.expense.dto.ExpenseDtos.PayRequest;
import com.myplus.expense.entity.ExpenseBillPayment;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/**
 * FP-3 — paying a bill. finance-service records the money (a PV- disbursement, Dr 2000 / Cr cash·bank, allocated to
 * the bill); this service makes sure it is recorded ONCE and that the bill and the subledger learn of it.
 *
 * <h3>Reserve → call → confirm, three short steps (finance's payment write has no duplicate guard of its own)</h3>
 * <ol>
 *   <li><b>Reserve</b> (own transaction, bill row-locked): a PENDING row keyed by the Idempotency-Key — the UNIQUE
 *       index refuses a second copy — checked against what is still owed after other PENDING reservations.</li>
 *   <li><b>Call</b> finance, outside any transaction (a slow ledger never holds the bill's lock), with
 *       {@code reference = EXPB-<org>-<row id>}.</li>
 *   <li><b>Confirm</b> (own transaction): row RECORDED with the PV number, the bill's paid amount stamped, the
 *       subledger told through the outbox.</li>
 * </ol>
 * If the answer is lost (a timeout), the row stays PENDING. The same key pressed again, or the reconciler a few
 * minutes later, first LOOKS FOR the reference in finance and only sends if it is not there — so a lost answer can
 * never become a second payment. A refusal finance actually gave (4xx) releases the reservation (FAILED).
 */
@Service
public class ExpenseBillService {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseBillService.class);
    static final String SOURCE_MODULE = "EXPENSE";

    private final ExpenseVoucherRepo vouchers;
    private final ExpenseBillPaymentRepo payments;
    private final ExpenseOutboxService outbox;
    private final ExpenseAuditService audit;
    private final ExpenseAccess access;
    private final ObjectProvider<FinanceClient> finance;
    private final TransactionTemplate tx;

    public ExpenseBillService(ExpenseVoucherRepo vouchers, ExpenseBillPaymentRepo payments, ExpenseOutboxService outbox,
                              ExpenseAuditService audit, ExpenseAccess access, ObjectProvider<FinanceClient> finance,
                              PlatformTransactionManager txm) {
        this.vouchers = vouchers;
        this.payments = payments;
        this.outbox = outbox;
        this.audit = audit;
        this.access = access;
        this.finance = finance;
        this.tx = new TransactionTemplate(txm);
    }

    /** The payments made against a bill the caller can see, oldest first. */
    public List<BillPaymentView> list(Long voucherId) {
        return tx.execute(st -> {
            visible(voucherId);
            return payments.findByVoucherIdOrderByIdAsc(voucherId).stream().map(BillPaymentView::of).toList();
        });
    }

    /** Pay (part of) a bill. A replayed Idempotency-Key returns the first payment — never a second one. */
    public BillPaymentView pay(Long voucherId, PayRequest r, String idempotencyKey) {
        access.assertModuleOn();
        Long org = access.org();
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        if (key == null || key.isEmpty()) throw new ValidationException("Missing Idempotency-Key header — the screen sends one per payment.");
        if (key.length() > 80) throw new ValidationException("Idempotency-Key is too long.");

        // 1 — reserve (or find the earlier attempt with this key)
        ExpenseBillPayment row;
        try {
            row = tx.execute(st -> reserve(org, voucherId, r, key));
        } catch (DataIntegrityViolationException raced) {
            throw new ValidationException("This payment is already being recorded. Refresh the bill.");
        }
        if (ExpenseBillPayment.RECORDED.equals(row.getStatus()) || ExpenseBillPayment.FAILED.equals(row.getStatus())) {
            return BillPaymentView.of(row);   // a replay of a finished attempt answers with its outcome
        }
        // 2 + 3
        return BillPaymentView.of(settle(row));
    }

    /**
     * Every minute: a reservation older than two minutes whose caller never learned the outcome. Found in finance →
     * confirmed; not there → released (FAILED), so the bill is open again. Runs as the user who reserved it, so
     * finance's scoped read sees that user's payments.
     */
    @Scheduled(fixedDelayString = "${expense.bill-payment.reconcile-ms:60000}", initialDelay = 60000)
    public void reconcilePending() {
        if (finance.getIfAvailable() == null) return;
        for (ExpenseBillPayment p : payments.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(
                ExpenseBillPayment.PENDING, LocalDateTime.now().minusMinutes(2))) {
            try {
                GatewayIdentityForwarding.runAs(p.getUserId(), p.getOrganizationId(), () -> {
                    Optional<PaymentView> found = findInFinance(p);
                    if (found.isPresent()) confirm(p.getId(), found.get().getId(), found.get().getReceiptNo());
                    else release(p.getId(), "Not recorded in the books — the payment did not go through.");
                });
            } catch (Exception e) {
                LOG.warn("bill payment {} could not be reconciled yet", p.getId(), e);
            }
        }
    }

    // ── FP-3b: reversing a payment ──────────────────────────────────────────────────────────────────

    /**
     * Reverse a payment made from the Expenses screen, so the bill owes it again (and, with nothing left paid, can be
     * voided). finance FIRST — its mirror payment and opposite journal are idempotent per payment, so a lost answer is
     * safe to press again — then, in one short transaction, the bill re-opened and the subledger told. A closed period
     * (or any refusal) answers in the books' words and changes nothing here. A Pay Supplier application is not
     * reversible here: its money is one payment for several bills, recorded by business-service.
     */
    public BillPaymentView reversePayment(Long voucherId, Long paymentId, String reason) {
        access.assertModuleOn();
        if (!access.seesAll()) throw new org.springframework.security.access.AccessDeniedException("Only an owner or admin can reverse a payment.");
        if (reason == null || reason.isBlank()) throw new ValidationException("Say why this payment is being reversed.");
        String why = reason.trim().length() > 255 ? reason.trim().substring(0, 255) : reason.trim();
        ExpenseBillPayment row = tx.execute(st -> {
            visible(voucherId);
            return payments.findById(paymentId)
                    .filter(p -> p.getVoucherId().equals(voucherId) && p.getOrganizationId().equals(access.org()))
                    .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        });
        if (ExpenseBillPayment.REVERSED.equals(row.getStatus())) return BillPaymentView.of(row);   // a retried reversal
        if (!ExpenseBillPayment.RECORDED.equals(row.getStatus()))
            throw new ValidationException("Only a recorded payment can be reversed (this one is " + row.getStatus() + ").");
        if (row.getFinancePaymentId() == null)
            throw new ValidationException("This was paid through Pay Supplier (" + row.getReference()
                    + ") — one payment for several bills — so it cannot be reversed from here.");

        PaymentRecordResult res;
        try {
            res = finance.getObject().reversePayment(row.getFinancePaymentId(), Map.of("reason", why));
        } catch (org.springframework.web.client.HttpClientErrorException refused) {
            throw new ValidationException("The books refused this reversal. " + messageOf(refused.getResponseBodyAsString()));
        } catch (Exception lost) {
            LOG.warn("reversal of bill payment {} sent, answer lost", row.getId(), lost);
            throw new ValidationException("The books did not answer in time. Press Reverse again — it will not reverse twice.");
        }
        return tx.execute(st -> {
            ExpenseBillPayment p = payments.findById(row.getId()).orElseThrow();
            if (ExpenseBillPayment.REVERSED.equals(p.getStatus())) return BillPaymentView.of(p);
            ExpenseVoucher v = vouchers.lockForPayment(p.getVoucherId(), p.getOrganizationId()).orElseThrow();
            try {
                v.reversePayment(p.getAmount());
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new ValidationException(e.getMessage());
            }
            v.setUpdatedAt(LocalDateTime.now());
            p.setStatus(ExpenseBillPayment.REVERSED);
            p.setReversalReceiptNo(res == null ? null : res.getReceiptNo());
            p.setReversalReason(why);
            p.setReversedBy(access.userId());
            p.setReversedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            payments.saveAndFlush(p);
            vouchers.saveAndFlush(v);
            outbox.enqueuePayable(v);      // the subledger: the bill owes this again
            audit.record("EXPENSE_BILL_PAYMENT_REVERSED", "EXPENSE", v.getVoucherNo(), p.getAmount(), p.getReceiptNo(), why);
            return BillPaymentView.of(p);
        });
    }

    /** The sentence of an ApiResponse error body ({"message": "..."}), else the body itself, trimmed. */
    static String messageOf(String body) {
        if (body == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        String out = m.find() ? m.group(1) : body.replaceAll("\\s+", " ");
        return out.length() > 200 ? out.substring(0, 200) : out;
    }

    // ── FP-5b: Pay Supplier settles bills too ───────────────────────────────────────────────────────

    /** The caller's supplier's bills that may still take a payment, with what each may still take. */
    public List<com.myplus.commerce.contracts.dto.OpenBillView> openBills(Long supplierId) {
        Long org = access.org();
        if (org == null || supplierId == null) return List.of();
        return tx.execute(st -> vouchers.findOpenBills(org, supplierId).stream()
                .map(v -> com.myplus.commerce.contracts.dto.OpenBillView.builder()
                        .id(v.getId()).voucherNo(v.getVoucherNo()).voucherDate(v.getVoucherDate()).dueDate(v.getDueDate())
                        .open(v.openAmount().subtract(payments.sumPending(v.getId())).max(BigDecimal.ZERO))
                        .build())
                .filter(b -> b.getOpen().signum() > 0)
                .toList());
    }

    /**
     * Part of a Pay Supplier payment, applied to one bill. The money is ALREADY in finance's ledger (business recorded
     * ONE payment for the whole Pay Supplier), so this creates no finance payment: it records the application, stamps
     * the bill and tells the subledger. Idempotent on (clientRef, bill) through the existing UNIQUE key, so a redelivery
     * applies once. Re-checked under the bill's row lock: never more than is still owed — anything the Expenses screen
     * paid in between is not paid twice; the difference stays in finance as the supplier's advance (and is logged).
     */
    public Map<String, Object> applyExternal(Long voucherId, com.myplus.commerce.contracts.dto.BillApplyRequest r) {
        Long org = access.org();
        if (org == null) throw new IllegalStateException("No tenant identity on the request");
        if (r == null || r.getClientRef() == null || r.getAmount() == null || r.getAmount().signum() <= 0)
            throw new ValidationException("An application needs a payment reference and an amount.");
        String key = applicationKey(r.getClientRef(), voucherId);
        BigDecimal applied = tx.execute(st -> {
            Optional<ExpenseBillPayment> earlier = payments.findByOrganizationIdAndIdempotencyKey(org, key);
            if (earlier.isPresent()) return earlier.get().getAmount();
            ExpenseVoucher v = vouchers.lockForPayment(voucherId, org).orElse(null);
            if (v == null || !v.isBill() || !ExpenseVoucher.POSTED.equals(v.getStatus())) return BigDecimal.ZERO;
            BigDecimal room = v.openAmount().subtract(payments.sumPending(v.getId())).max(BigDecimal.ZERO);
            BigDecimal take = r.getAmount().setScale(2, RoundingMode.HALF_UP).min(room);
            if (take.signum() <= 0) return BigDecimal.ZERO;
            ExpenseBillPayment p = new ExpenseBillPayment();
            p.setOrganizationId(org);
            p.setUserId(access.userId());
            p.setVoucherId(v.getId());
            p.setAmount(take);
            p.setMethod(r.getMethod() == null ? "CASH" : r.getMethod().trim().toUpperCase());
            p.setPaidOn(r.getPaidOn() == null ? TenantClock.today() : r.getPaidOn());
            p.setStatus(ExpenseBillPayment.RECORDED);
            p.setIdempotencyKey(key);
            String ref = r.getClientRef();
            p.setReference(ref.length() > 40 ? ref.substring(0, 40) : ref);   // the Pay Supplier it came from
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            payments.saveAndFlush(p);
            v.applyPayment(take);
            v.setUpdatedAt(LocalDateTime.now());
            vouchers.saveAndFlush(v);
            outbox.enqueuePayable(v);
            audit.record("EXPENSE_BILL_PAID", "EXPENSE", v.getVoucherNo(), take, p.getMethod(), "Pay Supplier " + ref);
            return take;
        });
        if (applied != null && applied.compareTo(r.getAmount()) < 0)
            LOG.warn("Pay Supplier {} meant {} for bill {} but only {} was still owed — the rest stands as the supplier's advance",
                    r.getClientRef(), r.getAmount(), voucherId, applied);
        return Map.of("applied", applied == null ? BigDecimal.ZERO : applied);
    }

    /** One key per (payment, bill), within the column's 80 characters whatever the reference's length. */
    static String applicationKey(String clientRef, Long voucherId) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((clientRef + ":" + voucherId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "AP:" + java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── steps ───────────────────────────────────────────────────────────────────────────────────────────

    private ExpenseBillPayment reserve(Long org, Long voucherId, PayRequest r, String key) {
        Optional<ExpenseBillPayment> earlier = payments.findByOrganizationIdAndIdempotencyKey(org, key);
        if (earlier.isPresent()) {
            if (!earlier.get().getVoucherId().equals(voucherId))
                throw new ValidationException("This payment key belongs to another bill.");
            return earlier.get();
        }
        visible(voucherId);
        ExpenseVoucher v = vouchers.lockForPayment(voucherId, org)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        if (!v.isBill()) throw new ValidationException("Only a bill can be paid — this expense was paid when it was recorded.");
        if (!ExpenseVoucher.POSTED.equals(v.getStatus()))
            throw new ValidationException("Only a posted bill can be paid (this one is " + v.getStatus() + ").");
        // The bill must be IN the books before money leaves against it: paying a bill whose posting failed would
        // debit Accounts Payable for a credit that was never made (STANDARDS §0b — never optimistic about money).
        if (!ExpenseVoucher.PS_POSTED_GL.equals(v.getPostingStatus()))
            throw new ValidationException("This bill is not in the books yet. Pay it once it shows In the books.");

        if (r == null || r.amount() == null || r.amount().signum() <= 0)
            throw new ValidationException("Enter the amount being paid.");
        BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal stillOwed = v.openAmount().subtract(payments.sumPending(v.getId()));
        if (amount.compareTo(stillOwed) > 0)
            throw new ValidationException("That is more than is owed on this bill (" + stillOwed.max(BigDecimal.ZERO) + ").");
        String method = method(r.method());
        LocalDate paidOn = r.paidOn() == null ? TenantClock.today() : r.paidOn();
        if (paidOn.isAfter(TenantClock.today())) throw new ValidationException("A payment cannot be dated in the future.");
        if (paidOn.isBefore(v.getVoucherDate())) throw new ValidationException("A bill cannot be paid before its own date.");

        ExpenseBillPayment p = new ExpenseBillPayment();
        p.setOrganizationId(org);
        p.setUserId(access.userId());
        p.setVoucherId(v.getId());
        p.setAmount(amount);
        p.setMethod(method);
        p.setPaidOn(paidOn);
        p.setStatus(ExpenseBillPayment.PENDING);
        p.setIdempotencyKey(key);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        p = payments.saveAndFlush(p);
        p.setReference("EXPB-" + org + "-" + p.getId());
        return payments.saveAndFlush(p);
    }

    /** Steps 2 + 3 for a PENDING row: look for it in finance first, send only if it is not there. */
    private ExpenseBillPayment settle(ExpenseBillPayment row) {
        FinanceClient f = finance.getIfAvailable();
        if (f == null) throw new ValidationException("The books are not reachable right now. Try again in a moment — it will not pay twice.");
        ExpenseVoucher v = vouchers.findById(row.getVoucherId()).orElseThrow(() -> new ResourceNotFoundException("Expense not found"));

        Optional<PaymentView> already;
        try {
            already = findInFinance(row);
        } catch (Exception e) {
            throw new ValidationException("Could not check the books for this payment. Try again — it will not pay twice.");
        }
        if (already.isPresent()) return confirm(row.getId(), already.get().getId(), already.get().getReceiptNo());

        PaymentRecordResult res;
        try {
            res = f.recordPayment(request(row, v));
        } catch (org.springframework.web.client.HttpClientErrorException refused) {
            // finance ANSWERED with a refusal (a closed period, a validation): nothing was recorded — release
            release(row.getId(), "The books refused this payment: " + refused.getStatusText());
            String body = refused.getResponseBodyAsString().replaceAll("\\s+", " ");
            throw new ValidationException("The books refused this payment. " + body.substring(0, Math.min(200, body.length())));
        } catch (Exception lost) {
            // no answer (timeout, connection reset) — it may or may not be recorded; keep the reservation PENDING
            LOG.warn("bill payment {} sent, answer lost — kept PENDING for the reconciler", row.getId(), lost);
            tx.executeWithoutResult(st -> payments.findById(row.getId()).ifPresent(p -> {
                p.setLastError("Waiting for the books to confirm: " + lost.getMessage());
                p.setUpdatedAt(LocalDateTime.now());
            }));
            throw new ValidationException("The books did not answer in time. Press Pay again in a moment — it will not pay twice.");
        }
        return confirm(row.getId(), res.getId(), res.getReceiptNo());
    }

    /** Step 3 — finance has the payment: stamp the row and the bill, and tell the subledger. Idempotent. */
    private ExpenseBillPayment confirm(Long rowId, Long financeId, String receiptNo) {
        return tx.execute(st -> {
            ExpenseBillPayment p = payments.findById(rowId).orElseThrow();
            if (ExpenseBillPayment.RECORDED.equals(p.getStatus())) return p;
            ExpenseVoucher v = vouchers.lockForPayment(p.getVoucherId(), p.getOrganizationId()).orElseThrow();
            v.applyPayment(p.getAmount());
            v.setUpdatedAt(LocalDateTime.now());
            p.setStatus(ExpenseBillPayment.RECORDED);
            p.setFinancePaymentId(financeId);
            p.setReceiptNo(receiptNo);
            p.setLastError(null);
            p.setUpdatedAt(LocalDateTime.now());
            vouchers.saveAndFlush(v);
            outbox.enqueuePayable(v);
            audit.record("EXPENSE_BILL_PAID", "EXPENSE", v.getVoucherNo(), p.getAmount(), p.getMethod(), receiptNo);
            return p;
        });
    }

    private void release(Long rowId, String why) {
        tx.executeWithoutResult(st -> payments.findById(rowId).ifPresent(p -> {
            if (!ExpenseBillPayment.PENDING.equals(p.getStatus())) return;
            p.setStatus(ExpenseBillPayment.FAILED);
            p.setLastError(why);
            p.setUpdatedAt(LocalDateTime.now());
        }));
    }

    private Optional<PaymentView> findInFinance(ExpenseBillPayment row) {
        ExpenseVoucher v = vouchers.findById(row.getVoucherId()).orElseThrow();
        List<PaymentView> list = finance.getObject().listPayments("VENDOR", v.getSupplierId());
        if (list == null) return Optional.empty();
        return list.stream().filter(pv -> row.getReference() != null && row.getReference().equals(pv.getReference())).findFirst();
    }

    static PaymentRecordRequest request(ExpenseBillPayment row, ExpenseVoucher v) {
        return PaymentRecordRequest.builder()
                .direction("DISBURSEMENT")
                .partyType("VENDOR").partyId(v.getSupplierId()).partyName(v.getSupplierName())
                .amount(row.getAmount()).method(row.getMethod()).paidOn(row.getPaidOn())
                .reference(row.getReference())
                .sourceModule(SOURCE_MODULE)
                .note("Bill " + v.getVoucherNo())
                .allocations(List.of(PaymentAllocationRef.builder()
                        .docType(VoucherPostings.PAYABLE_SOURCE).docId(v.getId()).docNo(v.getVoucherNo())
                        .amount(row.getAmount()).build()))
                .build();
    }

    static String method(String raw) {
        String m = raw == null ? "CASH" : raw.trim().toUpperCase();
        if (!m.equals("CASH") && !m.equals("BANK")) throw new ValidationException("Pay a bill by cash or bank.");
        return m;
    }

    private ExpenseVoucher visible(Long id) {
        ExpenseVoucher v = vouchers.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        Long only = access.visibleUserId();
        if (only != null && !only.equals(v.getUserId())) throw new ResourceNotFoundException("Expense not found");
        return v;
    }
}

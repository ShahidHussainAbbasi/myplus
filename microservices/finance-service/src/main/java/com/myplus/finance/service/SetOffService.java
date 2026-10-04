package com.myplus.finance.service;

import com.myplus.common.security.time.TenantClock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.finance.dto.AllocationDTO;
import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.dto.SetOffDTOs;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.entity.SetOff;
import com.myplus.finance.repository.PaymentRepository;
import com.myplus.finance.repository.SetOffRepository;

import lombok.RequiredArgsConstructor;

/**
 * DR-4 — both legs of a set-off in ONE transaction: a RECEIPT from the customer and a DISBURSEMENT to the supplier,
 * method SETOFF. They are recorded through {@link PaymentService#record}, the same path every receipt takes (receipt
 * numbers, allocations, its journal in the same transaction) — and because {@link PostingService#cashAccount} sends
 * SETOFF to 1900, the journals are Dr 1900 / Cr 1100 and Dr 2000 / Cr 1900. Either both legs commit or neither does.
 *
 * <h3>Idempotent</h3>
 * {@code uq_setoff_org_key}: a retry after business-service lost its commit answers the first document, posting
 * nothing. A concurrent duplicate loses on the UNIQUE index and rolls back whole.
 *
 * <h3>Reversal: mirror payments, never a flag</h3>
 * Statements add every payment as a credit, and party totals sum them. A flag on the originals would need every
 * reader to learn it; a MIRROR payment (negative amount, same party, method SETOFF) makes both net back on their own
 * and leaves the statement showing what happened — the set-off and its reversal. The journal is one entry mirroring
 * both legs ({@link PostingService#postSetOffReversal}), so 1900 nets to zero inside it.
 */
@Service
@RequiredArgsConstructor
public class SetOffService {

    public static final String METHOD = "SETOFF";

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final SetOffRepository setOffRepository;
    private final PostingService postingService;

    /** A request this ledger cannot accept — answered 400 with the sentence. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) { super(message); }
    }

    @Transactional
    public SetOffDTOs.Result record(SetOffDTOs.Request req) {
        Long org = CurrentUser.organizationId();
        if (req == null || blank(req.getIdempotencyKey())) throw new Rejected("idempotencyKey is required");
        var prior = setOffRepository.findByKey(org, req.getIdempotencyKey().trim());
        if (prior.isPresent()) return result(prior.get(), true);

        BigDecimal amount = req.getAmount();
        if (amount == null || amount.signum() <= 0) throw new Rejected("A positive amount is required");
        if (req.getCustomerId() == null || req.getVenderId() == null) throw new Rejected("customerId and venderId are required");
        // The allocations are what each leg settles; each side must account for exactly the set-off amount.
        if (sum(req.getCustomerAllocations()).compareTo(amount) != 0 || sum(req.getVendorAllocations()).compareTo(amount) != 0) {
            throw new Rejected("Each side's allocations must add up to the set-off amount");
        }
        LocalDate on = req.getPaidOn() != null ? req.getPaidOn() : TenantClock.today();
        String ref = blank(req.getReference()) ? req.getSetOffNo() : req.getReference();

        PaymentDTO receipt = paymentService.record(RecordPaymentRequest.builder()
                .direction(PaymentDirection.RECEIPT).partyType(PartyType.CUSTOMER)
                .partyId(req.getCustomerId()).partyName(req.getCustomerName())
                .amount(amount).method(METHOD).paidOn(on).reference(ref).sourceModule("BUSINESS")
                .note("Set-off " + req.getSetOffNo()).allocations(copy(req.getCustomerAllocations())).build());
        PaymentDTO disbursement = paymentService.record(RecordPaymentRequest.builder()
                .direction(PaymentDirection.DISBURSEMENT).partyType(PartyType.VENDOR)
                .partyId(req.getVenderId()).partyName(req.getVenderName())
                .amount(amount).method(METHOD).paidOn(on).reference(ref).sourceModule("BUSINESS")
                .note("Set-off " + req.getSetOffNo()).allocations(copy(req.getVendorAllocations())).build());

        SetOff s = new SetOff();
        s.setOrganizationId(org);
        s.setUserId(CurrentUser.userId());
        s.setIdempotencyKey(req.getIdempotencyKey().trim());
        s.setSetOffNo(req.getSetOffNo());
        s.setAmount(amount);
        s.setReceiptPaymentId(receipt.getId());
        s.setDisbursementPaymentId(disbursement.getId());
        s.setCreatedAt(LocalDateTime.now());
        setOffRepository.saveAndFlush(s);   // flush: a concurrent duplicate fails HERE, inside this transaction
        return new SetOffDTOs.Result(receipt.getReceiptNo(), disbursement.getReceiptNo(), false);
    }

    @Transactional
    public SetOffDTOs.Result reverse(SetOffDTOs.ReverseRequest req) {
        Long org = CurrentUser.organizationId();
        if (req == null || blank(req.getIdempotencyKey()) || blank(req.getReversalKey())) {
            throw new Rejected("idempotencyKey and reversalKey are required");
        }
        SetOff s = setOffRepository.findByKey(org, req.getIdempotencyKey().trim())
                .orElseThrow(() -> new Rejected("No such set-off in this ledger"));
        if (s.getReversedAt() != null) {
            if (req.getReversalKey().trim().equals(s.getReversalKey())) return result(s, true);   // a retried reversal
            throw new Rejected(s.getSetOffNo() + " is already reversed");
        }
        Payment receipt = paymentRepository.findById(s.getReceiptPaymentId()).orElseThrow(() -> new Rejected("Receipt leg missing"));
        Payment disbursement = paymentRepository.findById(s.getDisbursementPaymentId()).orElseThrow(() -> new Rejected("Payment leg missing"));
        LocalDate on = req.getReversedOn() != null ? req.getReversedOn() : TenantClock.today();
        String why = blank(req.getReason()) ? "" : ": " + req.getReason().trim();

        Payment mr = paymentRepository.save(mirror(receipt, on, "Reversal of " + s.getSetOffNo() + why));
        Payment md = paymentRepository.save(mirror(disbursement, on, "Reversal of " + s.getSetOffNo() + why));
        postingService.postSetOffReversal(s.getAmount(), on, s.getSetOffNo());

        s.setReversalKey(req.getReversalKey().trim());
        s.setReversalReason(blank(req.getReason()) ? null : req.getReason().trim());
        s.setReversedAt(LocalDateTime.now());
        s.setReversalReceiptId(mr.getId());
        s.setReversalDisbursementId(md.getId());
        setOffRepository.saveAndFlush(s);
        return new SetOffDTOs.Result(mr.getReceiptNo(), md.getReceiptNo(), false);
    }

    /**
     * The same payment with the opposite amount. No receipt_seq (NULL is allowed, and distinct, under
     * uq_pay_org_dir_seq) — its number is the original's with "-R", so the statement pairs the two by eye.
     * No allocations: business-service re-opens the documents itself, from its own record of what was cleared.
     */
    private static Payment mirror(Payment p, LocalDate on, String reference) {
        return Payment.builder()
                .direction(p.getDirection()).partyType(p.getPartyType()).partyId(p.getPartyId()).partyName(p.getPartyName())
                .amount(p.getAmount().negate()).method(METHOD).paidOn(on).reference(reference)
                .sourceModule(p.getSourceModule()).note(reference)
                .receiptNo(p.getReceiptNo() == null ? null : p.getReceiptNo() + "-R")
                .organizationId(p.getOrganizationId()).userId(CurrentUser.userId())
                .createdAt(LocalDateTime.now()).allocations(new ArrayList<>())
                .build();
    }

    private SetOffDTOs.Result result(SetOff s, boolean replay) {
        String rNo = paymentRepository.findById(s.getReceiptPaymentId()).map(Payment::getReceiptNo).orElse(null);
        String dNo = paymentRepository.findById(s.getDisbursementPaymentId()).map(Payment::getReceiptNo).orElse(null);
        return new SetOffDTOs.Result(rNo, dNo, replay);
    }

    private static List<AllocationDTO> copy(List<AllocationDTO> xs) {
        return xs == null ? new ArrayList<>() : new ArrayList<>(xs);
    }

    private static BigDecimal sum(List<AllocationDTO> xs) {
        BigDecimal t = BigDecimal.ZERO;
        if (xs != null) for (AllocationDTO a : xs) if (a != null && a.getAmount() != null) t = t.add(a.getAmount());
        return t;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}

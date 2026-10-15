package com.myplus.finance.service;

import com.myplus.common.security.time.TenantClock;

import com.myplus.common.docnum.DocumentNumberService;

import com.myplus.common.security.CurrentUser;
import com.myplus.finance.dto.AllocationDTO;
import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.entity.PaymentAllocation;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The payment ledger service. Records receipts/disbursements (with allocations) tenant-scoped and GL-ready, and
 * answers party history/totals. It does NOT allocate money itself — the owning module (which knows its invoices)
 * passes the allocations; finance-service faithfully records them so every module shares one ledger.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PostingService postingService;   // F3b: auto-post the receipt/disbursement to the GL
    private final DocumentNumberService documentNumberService;   // DOC-INT B: per-org receipt counters (V7)

    @Transactional
    public PaymentDTO record(RecordPaymentRequest req) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();

        // FP-5a — a caller delivering through an outbox may send the same settlement twice; the first payment answers.
        String clientRef = req.getClientRef() == null || req.getClientRef().isBlank() ? null : req.getClientRef().trim();
        if (clientRef != null) {
            java.util.Optional<Payment> first = paymentRepository.findByOrganizationIdAndClientRef(orgId, clientRef);
            if (first.isPresent()) return toDTO(first.get());
        }
        PaymentDirection direction = req.getDirection() != null ? req.getDirection() : PaymentDirection.RECEIPT;
        Payment p = Payment.builder()
                .direction(direction)
                .partyType(req.getPartyType())
                .partyId(req.getPartyId())
                .partyName(req.getPartyName())
                .amount(req.getAmount())
                .method(req.getMethod())
                .paidOn(req.getPaidOn() != null ? req.getPaidOn() : TenantClock.today())
                .reference(req.getReference())
                .sourceModule(req.getSourceModule())
                .note(req.getNote())
                .clientRef(clientRef)
                .organizationId(orgId)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .allocations(new ArrayList<>())
                .build();

        if (req.getAllocations() != null) {
            for (AllocationDTO a : req.getAllocations()) {
                if (a == null || a.getAmount() == null || a.getAmount().signum() <= 0) continue;
                p.addAllocation(PaymentAllocation.builder()
                        .docType(a.getDocType() != null ? a.getDocType() : "INVOICE")
                        .docId(a.getDocId()).docNo(a.getDocNo()).amount(a.getAmount())
                        .build());
            }
        }

        /*
         * DOC-INT B — the receipt number comes from the per-org counter, allocated LATE.
         *
         * It used to be COUNT(payments of this direction) + 1, and two receipts recorded together counted the same
         * total and took the same number — nothing refused the second (the local ledger held 2 such pairs).
         * Now the counter's row lock makes the second receipt wait, and uq_pay_org_dir_seq refuses a duplicate if
         * anything ever bypassed the counter.
         *
         * LATE, i.e. here: after the request is fully built and immediately before the insert, so the lock is held
         * only through this local insert and the local GL post below — never across a network call. The bump joins
         * this transaction (MANDATORY), so if the GL post refuses (a closed period), the number is given back.
         */
        long seq = documentNumberService.next(orgId, direction.name());
        p.setReceiptSeq(seq);
        p.setReceiptNo(receiptNo(direction, seq));

        Payment saved = paymentRepository.save(p);
        // Reliability: post the GL journal ATOMICALLY with the payment. finance owns BOTH the payment ledger and the
        // GL (same DB, same @Transactional — postPayment joins this tx), so a LOCAL transaction is the correct
        // atomicity tool here: NOT a best-effort swallow (which drifted the books — a recorded payment with no
        // journal), and NOT an outbox (that pattern is for CROSS-service hops; posting to our own GL in the same DB
        // needs no relay). postPayment ensureDefaults() seeds the CoA if missing and its journal balances by
        // construction, so it can only throw on a closed period (which must reject the payment too) or a real DB
        // fault (which would fail the save anyway) — either way payment + journal commit together or not at all.
        List<com.myplus.finance.dto.JournalLineDTO> posted = postingService.postPayment(saved.getDirection().name(),
                saved.getAmount(), saved.getMethod(), saved.getPartyType(), req.getPurpose());
        // EX-7b — keep on the row the accounts its journal moved (the V1 slots, unused until now), so a reversal
        // mirrors exactly what was posted
        if (posted != null && posted.size() == 2) {
            for (com.myplus.finance.dto.JournalLineDTO l : posted) {
                if (l.getDebit() != null && l.getDebit().signum() > 0) saved.setDebitAccount(l.getAccountCode());
                else saved.setCreditAccount(l.getAccountCode());
            }
        }
        return toDTO(saved);
    }

    /**
     * FP-3b — reverse a recorded payment: a MIRROR payment (same direction, opposite amount, the original's number
     * with "-R") and the opposite journal (a disbursement: Dr cash·bank / Cr 2000), atomically — the same shape DR-4
     * uses for a set-off. Idempotent: the mirror carries {@code clientRef = REV:<id>}, so a second call (a retry, a
     * lost answer) finds the first reversal and answers with it, and UNIQUE (organization_id, client_ref) refuses a
     * concurrent second one. A closed period refuses it (the journal's own lock check), and nothing is saved.
     * No allocations on the mirror: the caller re-opens its own document (expense-service re-opens the bill and tells
     * the payables subledger), exactly as business-service does after a set-off reversal.
     */
    @Transactional
    public PaymentDTO reverse(Long paymentId, String reason) {
        Long orgId = CurrentUser.organizationId();
        if (orgId == null) throw new IllegalStateException("No tenant identity on the request");
        if (reason == null || reason.isBlank())
            throw new com.myplus.common.web.exception.ValidationException("Say why this payment is being reversed.");
        Payment p = paymentRepository.findByIdAndOrganizationId(paymentId, orgId)
                .orElseThrow(() -> new com.myplus.common.web.exception.ResourceNotFoundException("Payment not found"));
        String clientRef = "REV:" + p.getId();
        java.util.Optional<Payment> earlier = paymentRepository.findByOrganizationIdAndClientRef(orgId, clientRef);
        if (earlier.isPresent()) return toDTO(earlier.get());
        if (p.getAmount() == null || p.getAmount().signum() <= 0)
            throw new com.myplus.common.web.exception.ValidationException("A reversal cannot itself be reversed.");
        if ("SETOFF".equalsIgnoreCase(p.getMethod()))
            throw new com.myplus.common.web.exception.ValidationException("A set-off is reversed from the supplier's statement, not here.");
        LocalDate on = TenantClock.today();
        String ref = "Reversal of " + p.getReceiptNo() + ": " + reason.trim();
        Payment mirror = Payment.builder()
                .direction(p.getDirection()).partyType(p.getPartyType()).partyId(p.getPartyId()).partyName(p.getPartyName())
                .amount(p.getAmount().negate()).method(p.getMethod()).paidOn(on)
                .reference(ref.length() > 255 ? ref.substring(0, 255) : ref)
                .sourceModule(p.getSourceModule()).note(ref.length() > 255 ? ref.substring(0, 255) : ref)
                .clientRef(clientRef)
                .receiptNo(p.getReceiptNo() == null ? null : p.getReceiptNo() + "-R")
                .organizationId(orgId).userId(CurrentUser.userId())
                .createdAt(LocalDateTime.now()).allocations(new ArrayList<>())
                .build();
        Payment saved = paymentRepository.saveAndFlush(mirror);
        if (p.getDebitAccount() != null && p.getCreditAccount() != null)
            postingService.postPaymentReversalOf(p.getDebitAccount(), p.getCreditAccount(), p.getAmount(), on, saved.getReceiptNo());
        else
            postingService.postPaymentReversal(p.getDirection().name(), p.getAmount(), p.getMethod(), on, saved.getReceiptNo(), p.getPartyType());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<PaymentDTO> listByParty(PartyType partyType, Long partyId) {
        List<PaymentDTO> out = new ArrayList<>();
        for (Payment p : paymentRepository.findByPartyScoped(partyType, partyId,
                CurrentUser.organizationId(), CurrentUser.userId())) {
            out.add(toDTO(p));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public BigDecimal totalByParty(PartyType partyType, Long partyId) {
        BigDecimal sum = paymentRepository.sumByPartyScoped(partyType, partyId,
                CurrentUser.organizationId(), CurrentUser.userId());
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * The printed number: RECEIPT → RCPT-######, DISBURSEMENT (AP) → PV-###### (payment voucher). Each direction
     * is its own series per org (V7 keys the counter on the direction), so receipts and vouchers never share one
     * running number. Unchanged format — every reader of receipt_no sees the same shape as before.
     */
    static String receiptNo(PaymentDirection direction, long seq) {
        String prefix = direction == PaymentDirection.DISBURSEMENT ? "PV" : "RCPT";
        return String.format("%s-%06d", prefix, seq);
    }

    private PaymentDTO toDTO(Payment p) {
        List<AllocationDTO> allocs = new ArrayList<>();
        for (PaymentAllocation a : p.getAllocations()) {
            allocs.add(AllocationDTO.builder()
                    .docType(a.getDocType()).docId(a.getDocId()).docNo(a.getDocNo()).amount(a.getAmount())
                    .build());
        }
        return PaymentDTO.builder()
                .id(p.getId()).direction(p.getDirection())
                .partyType(p.getPartyType()).partyId(p.getPartyId()).partyName(p.getPartyName())
                .amount(p.getAmount()).method(p.getMethod()).paidOn(p.getPaidOn())
                .reference(p.getReference()).sourceModule(p.getSourceModule())
                .receiptNo(p.getReceiptNo()).note(p.getNote()).createdAt(p.getCreatedAt())
                .allocations(allocs)
                .build();
    }
}

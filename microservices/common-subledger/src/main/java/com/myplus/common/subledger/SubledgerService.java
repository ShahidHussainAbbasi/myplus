package com.myplus.common.subledger;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentAllocationRef;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The ONE subledger settlement path, shared by AR (Receive Payment) and AP (Pay Vendor) — and reusable by any
 * future vertical (education fees, welfare pledges…). Given a party, an amount and the party's open documents
 * (oldest first), it: FIFO-allocates the amount across them, records the entry in the shared finance-service
 * ledger (best-effort — a ledger hiccup never blocks the settlement), and returns the outcome. This removes the
 * duplicated allocate+record logic that used to live in both CustomerService.receivePayment and payVendor (DRY/SOLID).
 */
@Service
public class SubledgerService {

    private static final Logger LOG = LoggerFactory.getLogger(SubledgerService.class);

    @Autowired(required = false)
    private FinanceClient financeClient;   // shared payment ledger; null if finance-service isn't wired

    /** FP-5a — the outbox every settlement's payment goes through (exactly once). Absent only in unit tests. */
    @Autowired(required = false)
    private LedgerOutbox ledgerOutbox;

    /**
     * Allocate {@code amount} FIFO across {@code openDocs} (already ordered oldest-first by the caller), recompute
     * the party balance via {@code recomputeAndGetDue}, and record the ledger entry. Runs in the caller's transaction.
     *
     * @param direction  RECEIPT (AR) | DISBURSEMENT (AP)
     * @param recomputeAndGetDue recomputes the party's running balance and returns the fresh value
     */
    /**
     * FIFO-allocate {@code amount} across {@code openDocs} (already oldest-first) and APPLY it to each document.
     * Returns what was allocated where; anything unallocated is the caller's to deal with.
     *
     * Public and separate from {@link #settle} because not every settlement is a cash receipt. Paying a school
     * fee from credit the school already holds moves a liability, not cash — it must reduce the dues without
     * recording a second payment in the ledger, or the same money would be counted as received twice.
     */
    public List<PaymentAllocationRef> allocate(List<? extends OpenDoc> openDocs, BigDecimal amount) {
        List<PaymentAllocationRef> allocations = new ArrayList<>();
        BigDecimal remaining = amount == null ? BigDecimal.ZERO : amount;
        for (OpenDoc doc : openDocs) {
            if (remaining.signum() <= 0) break;
            BigDecimal outstanding = doc.outstanding();
            if (outstanding == null || outstanding.signum() <= 0) continue;
            BigDecimal applied = remaining.min(outstanding);
            doc.apply(applied);
            allocations.add(PaymentAllocationRef.builder()
                    .docType(doc.docType()).docId(doc.docId()).docNo(doc.docNo()).amount(applied).build());
            remaining = remaining.subtract(applied);
        }
        return allocations;
    }

    public SettleOutcome settle(String direction, String partyType, Long partyId, String partyName,
                                BigDecimal amount, String method, LocalDate paidOn, String reference, String sourceModule,
                                List<? extends OpenDoc> openDocs, Supplier<BigDecimal> recomputeAndGetDue) {
        return settle(direction, partyType, partyId, partyName, amount, method, paidOn, reference, sourceModule,
                openDocs, recomputeAndGetDue, "SUB-" + java.util.UUID.randomUUID());
    }

    /**
     * FP-5a — as above, with the CALLER's reference for this settlement. A caller that has an idempotency key builds it
     * from that key, so a replayed request carries the same reference and finance answers it with the first payment.
     */
    public SettleOutcome settle(String direction, String partyType, Long partyId, String partyName,
                                BigDecimal amount, String method, LocalDate paidOn, String reference, String sourceModule,
                                List<? extends OpenDoc> openDocs, Supplier<BigDecimal> recomputeAndGetDue, String clientRef) {
        if (partyId == null) throw new RuntimeException("partyId is required");
        if (amount == null || amount.signum() <= 0) throw new RuntimeException("A positive amount is required");

        List<PaymentAllocationRef> allocations = allocate(openDocs, amount);
        BigDecimal remaining = amount.subtract(
                allocations.stream().map(PaymentAllocationRef::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        // Recompute the party's running balance from the (now-updated) docs before we report the new due.
        BigDecimal newDue = recomputeAndGetDue.get();

        PaymentRecordRequest ledgerRequest = PaymentRecordRequest.builder()
                .direction(direction).partyType(partyType).partyId(partyId).partyName(partyName)
                .amount(amount).method(method).paidOn(paidOn).reference(reference)
                .sourceModule(sourceModule).allocations(allocations).clientRef(clientRef).build();
        // FP-5a — the payment travels by outbox, in THIS transaction: applied documents and the ledger request commit
        // together or not at all, and the request is delivered exactly once (see LedgerOutbox).
        if (ledgerOutbox != null) {
            ledgerOutbox.enqueue(ledgerRequest);
            return new SettleOutcome(null, amount.subtract(remaining), remaining, newDue, clientRef);
        }

        // Legacy path (no outbox wired — unit tests only): best-effort direct call.
        String voucherNo = null;
        try {
            if (financeClient != null) {
                PaymentRecordResult res = financeClient.recordPayment(ledgerRequest);
                voucherNo = res != null ? res.getReceiptNo() : null;
            }
        } catch (Exception ex) {
            LOG.warn("finance ledger record failed for {} {} ({}; settlement applied, reconcile later)",
                    partyType, partyId, direction, ex);
        }

        return new SettleOutcome(voucherNo, amount.subtract(remaining), remaining, newDue, clientRef);
    }
}

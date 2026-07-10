package com.myplus.business_service.service.subledger;

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

    /**
     * Allocate {@code amount} FIFO across {@code openDocs} (already ordered oldest-first by the caller), recompute
     * the party balance via {@code recomputeAndGetDue}, and record the ledger entry. Runs in the caller's transaction.
     *
     * @param direction  RECEIPT (AR) | DISBURSEMENT (AP)
     * @param recomputeAndGetDue recomputes the party's running balance and returns the fresh value
     */
    public SettleOutcome settle(String direction, String partyType, Long partyId, String partyName,
                                BigDecimal amount, String method, LocalDate paidOn, String reference, String sourceModule,
                                List<? extends OpenDoc> openDocs, Supplier<BigDecimal> recomputeAndGetDue) {
        if (partyId == null) throw new RuntimeException("partyId is required");
        if (amount == null || amount.signum() <= 0) throw new RuntimeException("A positive amount is required");

        List<PaymentAllocationRef> allocations = new ArrayList<>();
        BigDecimal remaining = amount;
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

        // Recompute the party's running balance from the (now-updated) docs before we report the new due.
        BigDecimal newDue = recomputeAndGetDue.get();

        // Record in the shared ledger — best-effort: the settlement is already applied; reconcile later on a hiccup.
        String voucherNo = null;
        try {
            if (financeClient != null) {
                PaymentRecordResult res = financeClient.recordPayment(PaymentRecordRequest.builder()
                        .direction(direction).partyType(partyType).partyId(partyId).partyName(partyName)
                        .amount(amount).method(method).paidOn(paidOn).reference(reference)
                        .sourceModule(sourceModule).allocations(allocations).build());
                voucherNo = res != null ? res.getReceiptNo() : null;
            }
        } catch (Exception ex) {
            LOG.warn("finance ledger record failed for {} {} ({}; settlement applied, reconcile later)",
                    partyType, partyId, direction, ex);
        }

        return new SettleOutcome(voucherNo, amount.subtract(remaining), remaining, newDue);
    }
}

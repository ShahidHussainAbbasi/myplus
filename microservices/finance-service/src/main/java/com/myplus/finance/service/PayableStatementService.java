package com.myplus.finance.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.subledger.StatementBuilder;
import com.myplus.common.subledger.StatementLine;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.PayableDoc;
import com.myplus.finance.entity.PayableNoteRow;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.repository.PayableDocRepository;
import com.myplus.finance.repository.PayableNoteRepository;
import com.myplus.finance.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

/**
 * FP-4a — a supplier's statement of account, served from finance's payables subledger.
 *
 * <h3>Built to read exactly like business's statement</h3>
 * Same three kinds of line, in the same input order, through the SAME {@link StatementBuilder} (stable sort by date,
 * running balance): BILL = the bill as issued, DEBIT_NOTE = each return against it, PAYMENT = each payment in the
 * ledger, newest-id first as {@code findByPartyScoped} returns them, a set-off named by its document (DR-4).
 * With {@code purchasesOnly} it is business's view (purchases, and payments not made from Expenses) — what the
 * reconciliation gate compares line for line. Without it, expense bills and their payments are on it too.
 *
 * <h3>A voided document</h3>
 * A voided PURCHASE stays (its debit note offsets it — business shows both). A voided EXPENSE BILL is left off: it is
 * reversed by a journal, it has no debit note, so its bill line would stand unanswered.
 */
@Service
@RequiredArgsConstructor
public class PayableStatementService {

    static final String PURCHASE = "PURCHASE", EXPENSE_MODULE = "EXPENSE";

    private final PayableDocRepository docs;
    private final PayableNoteRepository notes;
    private final PaymentRepository payments;

    @Transactional(readOnly = true)
    public List<StatementLine> statement(String partyType, Long partyId, boolean purchasesOnly) {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new IllegalStateException("No tenant identity on the request");
        String type = partyType == null ? "VENDOR" : partyType.trim().toUpperCase();
        List<PayableDoc> mine = docs.findByOrganizationIdAndPartyTypeAndPartyIdOrderByDocDateAscIdAsc(org, type, partyId);
        List<Long> ids = new ArrayList<>();
        for (PayableDoc d : mine) ids.add(d.getId());
        Map<Long, List<PayableNoteRow>> byDoc = new HashMap<>();
        if (!ids.isEmpty()) for (PayableNoteRow n : notes.findByPayableDocIdIn(ids))
            byDoc.computeIfAbsent(n.getPayableDocId(), k -> new ArrayList<>()).add(n);
        List<Payment> paid = payments.findByPartyScoped(PartyType.valueOf(type), partyId, org, CurrentUser.userId());
        return build(mine, byDoc, paid, purchasesOnly);
    }

    /** Pure — the whole statement from its three inputs (unit-tested). */
    static List<StatementLine> build(List<PayableDoc> mine, Map<Long, List<PayableNoteRow>> byDoc, List<Payment> paid,
                                     boolean purchasesOnly) {
        List<StatementLine> lines = new ArrayList<>();
        List<PayableNoteRow> allNotes = new ArrayList<>();
        for (PayableDoc d : mine) {
            boolean purchase = PURCHASE.equals(d.getSource());
            if (purchasesOnly && !purchase) continue;
            if (!purchase && PayableDoc.VOID.equals(d.getStatus())) continue;
            BigDecimal issued = d.getIssuedAmount() != null ? d.getIssuedAmount() : d.getAmount();
            lines.add(new StatementLine(d.getDocDate(), d.getDocNo(), "BILL", issued, null, null));
            allNotes.addAll(byDoc.getOrDefault(d.getId(), List.of()));
        }
        // business appends every debit note after every bill; same order here, before the stable sort
        for (PayableNoteRow n : allNotes)
            lines.add(new StatementLine(n.getNoteDate(), n.getNoteNo(), "DEBIT_NOTE", null, n.getAmount(), null));
        for (Payment p : paid) {
            if (purchasesOnly && EXPENSE_MODULE.equalsIgnoreCase(p.getSourceModule())) continue;
            String doc = "SETOFF".equalsIgnoreCase(p.getMethod()) && p.getReference() != null
                    ? p.getReference() + " (" + p.getReceiptNo() + ")" : p.getReceiptNo();
            lines.add(new StatementLine(p.getPaidOn(), doc, "PAYMENT", null,
                    p.getAmount() == null ? BigDecimal.ZERO : p.getAmount(), null));
        }
        return StatementBuilder.build(lines, BigDecimal.ZERO);
    }
}

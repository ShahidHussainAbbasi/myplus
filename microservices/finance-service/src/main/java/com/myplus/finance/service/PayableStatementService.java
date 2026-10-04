package com.myplus.finance.service;

import com.myplus.common.security.time.TenantClock;

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

    /**
     * FP-4b — supplier aging from the subledger: every OPEN document, bucketed by when it is due ({@code due_date},
     * else its date — a purchase has no terms, so it ages from the bill exactly as business does), with the same
     * {@code AgingCalculator}. Beside it, the suppliers paid AHEAD: net across all live documents below zero (ruling 1 —
     * an advance is its own figure, never netted into the aging).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> aging() {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new IllegalStateException("No tenant identity on the request");
        return aging(docs.findOpen(org), docs.netBySupplier(org), TenantClock.today());
    }

    static Map<String, Object> aging(List<PayableDoc> open, List<Object[]> nets, java.time.LocalDate asOf) {
        Map<Long, List<com.myplus.common.subledger.AgingCalculator.AgingRow>> byParty = new java.util.LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (PayableDoc d : open) {
            java.time.LocalDate from = d.getDueDate() != null ? d.getDueDate() : d.getDocDate();
            byParty.computeIfAbsent(d.getPartyId(), k -> new ArrayList<>())
                    .add(new com.myplus.common.subledger.AgingCalculator.AgingRow(d.open(), from));
            names.putIfAbsent(d.getPartyId(), d.getPartyName());
        }
        List<com.myplus.common.subledger.PartyAgingDTO> rows = new ArrayList<>();
        for (Map.Entry<Long, List<com.myplus.common.subledger.AgingCalculator.AgingRow>> e : byParty.entrySet()) {
            BigDecimal[] b = com.myplus.common.subledger.AgingCalculator.bucketize(e.getValue(), asOf);
            BigDecimal total = com.myplus.common.subledger.AgingCalculator.total(b);
            if (total.signum() <= 0) continue;
            rows.add(new com.myplus.common.subledger.PartyAgingDTO(e.getKey(), names.get(e.getKey()), b[0], b[1], b[2], b[3], total));
        }
        rows.sort((x, y) -> y.getTotal().compareTo(x.getTotal()));   // biggest owed first, as business sorts
        List<Map<String, Object>> advances = new ArrayList<>();
        for (Object[] r : nets) {
            BigDecimal net = r[2] == null ? BigDecimal.ZERO : (BigDecimal) r[2];
            if (net.signum() >= 0) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("partyId", r[0]);
            m.put("partyName", r[1]);
            m.put("advance", net.negate());
            advances.add(m);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", rows);
        out.put("advances", advances);
        return out;
    }

    /**
     * "OPENING" for a supplier opening balance, "BILL" for everything else — the same name business's statement gives
     * it (FinanceReportService.creditLineType), so FP-4a's line-for-line comparison still holds. The snapshot carries
     * no document type, but an opening balance is the only PURCHASE numbered in the per-business OB- series
     * (OpeningBalanceService: String.format("OB-%06d"); every other bill carries the supplier's own invoice number,
     * so a supplier invoice that happened to start "OB-" is the one case this would name wrongly — display only).
     */
    static String lineType(PayableDoc d) {
        return PURCHASE.equals(d.getSource()) && d.getDocNo() != null && d.getDocNo().matches("OB-\\d{6}") ? "OPENING" : "BILL";
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
            lines.add(new StatementLine(d.getDocDate(), d.getDocNo(), lineType(d), issued, null, null));
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

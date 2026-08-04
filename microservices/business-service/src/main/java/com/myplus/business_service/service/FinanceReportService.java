package com.myplus.business_service.service;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.entity.Vender;
import com.myplus.common.subledger.AgingCalculator;
import com.myplus.common.subledger.AgingCalculator.AgingRow;
import com.myplus.common.subledger.PartyAgingDTO;
import com.myplus.common.subledger.StatementBuilder;
import com.myplus.common.subledger.StatementLine;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * F2: party statements + aging — a pure read/projection over AR (CustomerHistory) + AP (Purchase) that business-
 * service owns, plus the shared finance ledger for statement payment lines. Aging needs NO finance call; a
 * statement makes one on-demand finance read. The bucketing/running-balance maths lives in the party-agnostic
 * {@link AgingCalculator}/{@link StatementBuilder} (one path for AR + AP, no duplication).
 */
@Service
public class FinanceReportService {

    @Autowired private CustomerHistoryRepo customerHistoryRepo;
    @Autowired private PurchaseRepo purchaseRepo;
    @Autowired private CustomerRepo customerRepo;
    @Autowired private VenderRepo venderRepo;
    @Autowired private RequestUtil requestUtil;
    @Autowired(required = false) private com.myplus.commerce.contracts.client.FinanceClient financeClient;
    // B2B-P3f: the return documents the statement now shows. required=false mirrors financeClient above so a
    // slim test context that wires neither still builds a statement — one without note lines, exactly as before.
    @Autowired(required = false) private com.myplus.business_service.repository.SaleReturnRepo saleReturnRepo;
    @Autowired(required = false) private com.myplus.business_service.repository.PurchaseReturnRepo purchaseReturnRepo;

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private boolean inTenant(Long rowOrg, Long rowUser, AuthenticatedUser u) {
        return (rowOrg != null && rowOrg.equals(u.getOrganizationId()))
                || (rowOrg == null && rowUser != null && rowUser.equals(u.getUserId()));
    }

    // ---- Aging ------------------------------------------------------------------------------------------------

    /** AR aging: buckets every customer's still-owing invoices (age basis = due date, else invoice date). */
    @Transactional(readOnly = true)
    public List<PartyAgingDTO> customerAging() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        LocalDate asOf = LocalDate.now();
        Map<Long, List<AgingRow>> byParty = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (CustomerHistory ch : customerHistoryRepo.findOpenInvoicesScoped(u.getOrganizationId(), u.getUserId())) {
            Customer c = ch.getCustomer();
            if (c == null || c.getCustomerId() == null) continue;
            names.putIfAbsent(c.getCustomerId(), c.getName());
            LocalDate ageDate = ch.getDueDate() != null ? ch.getDueDate()
                    : (ch.getDated() != null ? ch.getDated().toLocalDate() : asOf);
            byParty.computeIfAbsent(c.getCustomerId(), k -> new ArrayList<>())
                    .add(new AgingRow(nz(ch.getDueAmount()).negate(), ageDate));   // due = paid − bill (neg while owing)
        }
        return toAging(byParty, names, asOf);
    }

    /** AP aging: buckets every vendor's still-owing bills (Purchase has no due date → age basis = purchase date). */
    @Transactional(readOnly = true)
    public List<PartyAgingDTO> vendorAging() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        LocalDate asOf = LocalDate.now();
        Map<Long, List<AgingRow>> byParty = new LinkedHashMap<>();
        List<Purchase> bills = purchaseRepo.findOpenBillsScoped(u.getOrganizationId(), u.getUserId());
        for (Purchase p : bills) {
            LocalDate ageDate = p.getDated() != null ? p.getDated().toLocalDate() : asOf;
            byParty.computeIfAbsent(p.getVenderId(), k -> new ArrayList<>())
                    .add(new AgingRow(nz(p.getDueAmount()).negate(), ageDate));
        }
        Map<Long, String> names = new HashMap<>();
        for (Vender v : venderRepo.findAllById(byParty.keySet())) names.put(v.getId(), v.getName());
        return toAging(byParty, names, asOf);
    }

    private List<PartyAgingDTO> toAging(Map<Long, List<AgingRow>> byParty, Map<Long, String> names, LocalDate asOf) {
        List<PartyAgingDTO> out = new ArrayList<>();
        for (Map.Entry<Long, List<AgingRow>> e : byParty.entrySet()) {
            BigDecimal[] b = AgingCalculator.bucketize(e.getValue(), asOf);
            BigDecimal total = AgingCalculator.total(b);
            if (total.signum() <= 0) continue;   // no outstanding → not on the aging report
            out.add(new PartyAgingDTO(e.getKey(), names.get(e.getKey()), b[0], b[1], b[2], b[3], total));
        }
        out.sort((a, c) -> c.getTotal().compareTo(a.getTotal()));   // biggest owed first
        return out;
    }

    // ---- Statement of account ----------------------------------------------------------------------------------

    /** AR statement: the customer's invoices (BILL/debit) + receipts (PAYMENT/credit) with a running balance. */
    @Transactional(readOnly = true)
    public List<StatementLine> customerStatement(Long customerId) {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        Customer c = customerRepo.findById(customerId).orElse(null);
        if (c == null || !inTenant(c.getOrganizationId(), c.getUserId(), u))
            throw new RuntimeException("Customer not found: " + customerId);   // anti-IDOR
        List<StatementLine> lines = new ArrayList<>();
        List<String> invoiceNos = new ArrayList<>();
        for (CustomerHistory ch : customerHistoryRepo.findByCustomerOrdered(customerId)) {
            // B2B-P3f: the bill is the invoice AS ISSUED, not its settled value — a return no longer rewrites
            // this line, a credit note explains it below. coalesce: a legacy row with no issuedTotal falls back
            // to grandTotal and therefore renders exactly as it did before V34.
            BigDecimal issued = ch.getIssuedTotal() != null ? ch.getIssuedTotal() : nz(ch.getGrandTotal());
            lines.add(new StatementLine(ch.getDated() != null ? ch.getDated().toLocalDate() : null,
                    ch.getInvoiceNo(), "BILL", issued, null, null));
            if (ch.getInvoiceNo() != null) invoiceNos.add(ch.getInvoiceNo());

            // A VOID zeroed the header, so the issued bill above needs its cancellation or the invoice would be
            // overstated by its full value. Guarded on > 0 so a pre-V34 void (back-filled to 0) adds no line.
            if ("VOID".equals(ch.getStatus()) && issued.signum() > 0) {
                lines.add(new StatementLine(ch.getVoidedAt() != null ? ch.getVoidedAt().toLocalDate() : null,
                        ch.getInvoiceNo(), "VOID", null, issued, null));
            }
        }
        addCreditNoteLines(lines, invoiceNos);
        addPaymentLines(lines, "CUSTOMER", customerId);
        return StatementBuilder.build(lines, BigDecimal.ZERO);
    }

    /**
     * B2B-P3f: the customer's credit notes as CREDIT_NOTE credit lines — the document that explains why the
     * balance fell, which the statement never showed.
     *
     * <p>One batched query over the invoices already loaded (SaleReturn has no customerId). Notes taken before
     * V34 have no stored value and are excluded by the repository: their value is unrecoverable, and a
     * fabricated figure on a document a customer reconciles against is worse than an absent one. The balance
     * stays correct either way, because those invoices' bills are back-filled to their already-netted value.
     */
    private void addCreditNoteLines(List<StatementLine> lines, List<String> invoiceNos) {
        if (saleReturnRepo == null || invoiceNos.isEmpty()) return;
        AuthenticatedUser u = requestUtil.getCurrentUser();
        for (var cn : saleReturnRepo.findCreditNotesForInvoices(invoiceNos, u.getOrganizationId(), u.getUserId())) {
            lines.add(new StatementLine(cn.getDated() != null ? cn.getDated().toLocalDate() : null,
                    cn.getCreditNoteNo() != null ? cn.getCreditNoteNo() : cn.getInvoiceNo(),
                    "CREDIT_NOTE", null, nz(cn.getCreditAmount()), null));
        }
    }

    /** AP statement: the vendor's bills (BILL/debit) + our payments (PAYMENT/credit) with a running balance. */
    @Transactional(readOnly = true)
    public List<StatementLine> vendorStatement(Long venderId) {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        Vender v = venderRepo.findById(venderId).orElse(null);
        if (v == null || !inTenant(v.getOrganizationId(), v.getUserId(), u))
            throw new RuntimeException("Vendor not found: " + venderId);   // anti-IDOR
        List<StatementLine> lines = new ArrayList<>();
        for (Purchase p : purchaseRepo.findByVenderOrdered(venderId)) {
            // B2B-P3f: the bill AS ISSUED, GROSS. Note the fallback is totalAmount + taxAmount, not totalAmount
            // alone as this line read before — the bill you owe a supplier includes its input tax, which is the
            // basis dueAmount and the debit notes both use. Only orgs that capture purchase tax see a change,
            // and for them the statement previously disagreed with the payable it was meant to explain.
            BigDecimal issued = p.getIssuedTotal() != null ? p.getIssuedTotal()
                    : nz(p.getTotalAmount()).add(nz(p.getTaxAmount()));
            lines.add(new StatementLine(p.getDated() != null ? p.getDated().toLocalDate() : null,
                    p.getPurchaseInvoiceNo(), "BILL", issued, null, null));
        }
        addDebitNoteLines(lines, venderId);
        addPaymentLines(lines, "VENDOR", venderId);
        return StatementBuilder.build(lines, BigDecimal.ZERO);
    }

    /**
     * B2B-P3f: the vendor's debit notes as DEBIT_NOTE credit lines — the supplier side of the same trail.
     *
     * <p>No cutover filter, unlike the sale side: {@code PurchaseReturn.amount} has been persisted since 3c,
     * so history is complete here. Queried by vendor directly (PurchaseReturn carries venderId), so no join
     * through the bills is needed.
     */
    private void addDebitNoteLines(List<StatementLine> lines, Long venderId) {
        if (purchaseReturnRepo == null) return;
        AuthenticatedUser u = requestUtil.getCurrentUser();
        for (var dn : purchaseReturnRepo.findDebitNotesForVender(venderId, u.getOrganizationId(), u.getUserId())) {
            lines.add(new StatementLine(dn.getDated() != null ? dn.getDated().toLocalDate() : null,
                    dn.getDebitNoteNo() != null ? dn.getDebitNoteNo() : dn.getPurchaseInvoiceNo(),
                    "DEBIT_NOTE", null, nz(dn.getAmount()), null));
        }
    }

    /** Append the party's ledger payments as PAYMENT (credit) lines — best-effort (a ledger hiccup just omits them). */
    private void addPaymentLines(List<StatementLine> lines, String partyType, Long partyId) {
        if (financeClient == null) return;
        try {
            var payments = financeClient.listPayments(partyType, partyId);
            if (payments != null) for (var pay : payments) {
                lines.add(new StatementLine(pay.getPaidOn(), pay.getReceiptNo(), "PAYMENT", null, nz(pay.getAmount()), null));
            }
        } catch (Exception ignore) { /* statement still shows bills + running balance from them */ }
    }
}

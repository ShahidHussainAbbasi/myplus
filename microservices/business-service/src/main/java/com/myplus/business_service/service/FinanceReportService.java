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
        for (CustomerHistory ch : customerHistoryRepo.findByCustomerOrdered(customerId)) {
            lines.add(new StatementLine(ch.getDated() != null ? ch.getDated().toLocalDate() : null,
                    ch.getInvoiceNo(), "BILL", nz(ch.getGrandTotal()), null, null));
        }
        addPaymentLines(lines, "CUSTOMER", customerId);
        return StatementBuilder.build(lines, BigDecimal.ZERO);
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
            lines.add(new StatementLine(p.getDated() != null ? p.getDated().toLocalDate() : null,
                    p.getPurchaseInvoiceNo(), "BILL", nz(p.getTotalAmount()), null, null));
        }
        addPaymentLines(lines, "VENDOR", venderId);
        return StatementBuilder.build(lines, BigDecimal.ZERO);
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

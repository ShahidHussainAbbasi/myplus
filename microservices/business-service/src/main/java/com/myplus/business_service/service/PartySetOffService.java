package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.client.SetOffLedgerClient;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.PartySetOff;
import com.myplus.business_service.entity.PartySetOffAlloc;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.PartySetOffAllocRepo;
import com.myplus.business_service.repository.PartySetOffRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.subledger.OpenDoc;
import com.myplus.common.subledger.SubledgerService;
import com.myplus.commerce.contracts.dto.PaymentAllocationRef;

/**
 * DR-4 — set-off: a partner that is both our customer and our supplier settles what it owes us against what we owe it,
 * by agreement. Design: microservices/docs/party-dual-role-customer-supplier-analysis.md (DR-4).
 *
 * <h3>Never optimistic about money</h3>
 * One local transaction: guards → allocate both sides → recompute both balances → SETOFF number → finance records BOTH
 * legs atomically → save the document. If finance does not confirm, the exception rolls everything here back: no
 * balance moves without its journal (unlike Receive Payment's best-effort ledger record, F2). If our commit fails after
 * finance confirmed, the retry carries the same idempotency key and finance answers the first document (replay).
 *
 * <h3>What a set-off clears</h3>
 * The customer's ORDINARY open invoices (never an invoice carrying an installment plan, nor installment rows —
 * decision 3, so a plan's paid total only moves with real money) and the supplier's open bills, oldest first.
 *
 * <h3>The SETOFF number</h3>
 * Allocated BEFORE the finance call, against the usual "allocate late" rule, because finance prints it on both
 * statements. The counter row is per document TYPE, so its lock can only make another set-off of the same business
 * wait — never a till. Gapless still holds: a refused set-off rolls the number back with everything else.
 */
@Service
public class PartySetOffService {

    @Autowired private CustomerRepo customerRepo;
    @Autowired private VenderRepo venderRepo;
    @Autowired private CustomerHistoryRepo customerHistoryRepo;
    @Autowired private PurchaseRepo purchaseRepo;
    @Autowired private PartySetOffRepo setOffRepo;
    @Autowired private PartySetOffAllocRepo allocRepo;
    @Autowired private ICustomerService customerService;
    @Autowired private IVenderService venderService;
    @Autowired private PeriodLockGuard periodLockGuard;
    @Autowired private DocumentNumberService documentNumberService;
    @Autowired private SubledgerService subledgerService;
    @Autowired private AuditService auditService;
    @Autowired(required = false) private InstallmentPlanService installmentPlanService;
    @Autowired(required = false) private SetOffLedgerClient ledger;

    /** The form's answer — the same shape whether this call recorded the set-off or replayed an earlier one. */
    public record Outcome(Long id, String setOffNo, BigDecimal amount, String receiptNo, String voucherNo, boolean replay) {}

    private static Long org() { return CurrentUser.organizationId(); }
    private static Long user() { return CurrentUser.userId(); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }

    // ---- what can be set off ---------------------------------------------------------------------------------------

    /** The customer's ordinary open invoices, oldest first — never one that carries an installment plan. */
    private List<CustomerHistory> customerDocs(Long customerId) {
        Set<String> planInvoices = installmentPlanService == null ? Collections.emptySet()
                : new HashSet<>(installmentPlanService.planInvoiceNumbers(org(), customerId));
        List<CustomerHistory> out = new ArrayList<>();
        for (CustomerHistory inv : customerHistoryRepo.findOpenInvoicesByCustomer(customerId)) {
            if (inv.getInvoiceNo() != null && planInvoices.contains(inv.getInvoiceNo())) continue;
            if (nz(inv.getDueAmount()).signum() < 0) out.add(inv);   // due = paid − bill: negative while owing
        }
        return out;
    }

    private List<Purchase> vendorDocs(Long venderId) {
        List<Purchase> out = new ArrayList<>();
        for (Purchase b : purchaseRepo.findOpenPurchasesByVendor(venderId)) {
            if (nz(b.getDueAmount()).signum() < 0) out.add(b);       // due = paid − net: negative while we owe
        }
        return out;
    }

    /** What this customer record owes us on ordinary invoices — the receivable a set-off can clear. */
    public BigDecimal settleableReceivable(Long customerId) {
        return customerDocs(customerId).stream().map(i -> nz(i.getDueAmount()).negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What we owe this supplier record on its bills. */
    public BigDecimal settleablePayable(Long venderId) {
        return vendorDocs(venderId).stream().map(b -> nz(b.getDueAmount()).negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- set off -----------------------------------------------------------------------------------------------------

    @Transactional
    public Outcome setOff(Long customerId, Long venderId, BigDecimal amount, String reason, String reference,
                          boolean sameBusiness, String idempotencyKey) {
        Long org = org();
        if (blank(idempotencyKey)) throw new PartyRoleService.Refusal("This form is out of date. Reload the page and try again.");
        // A repeat of a set-off already recorded answers the first one — checked before anything else can refuse it.
        var prior = setOffRepo.findByKey(org, idempotencyKey.trim());
        if (prior.isPresent()) return outcome(prior.get(), true);

        if (!sameBusiness) throw new PartyRoleService.Refusal("Confirm that this customer and this supplier are the same business.");
        if (blank(reason)) throw new PartyRoleService.Refusal("Give a reason for the set-off.");
        if (amount == null || amount.signum() <= 0) throw new PartyRoleService.Refusal("Enter an amount greater than zero.");
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);

        Customer c = customerRepo.findByIdScoped(customerId, org, user()).orElseThrow(() -> new PartyRoleService.Refusal("Customer not found."));
        Vender v = venderRepo.findByIdScoped(venderId, org, user()).orElseThrow(() -> new PartyRoleService.Refusal("Supplier not found."));
        if (c.getPartyId() == null || !c.getPartyId().equals(v.getPartyId())) {
            throw new PartyRoleService.Refusal("This customer and this supplier are not the same partner. Link them first.");
        }
        LocalDate today = LocalDate.now();
        periodLockGuard.assertOpen(today);
        if (ledger == null) throw new PartyRoleService.Refusal("Set-off is not available in this installation.");

        List<CustomerHistory> invoices = customerDocs(customerId);
        List<Purchase> bills = vendorDocs(venderId);
        BigDecimal limit = sum(invoices.stream().map(i -> nz(i.getDueAmount()).negate()).toList())
                .min(sum(bills.stream().map(b -> nz(b.getDueAmount()).negate()).toList()));
        if (amount.compareTo(limit) > 0) {
            throw new PartyRoleService.Refusal("The most that can be set off is " + limit.toPlainString()
                    + " — the smaller of what they owe us and what we owe them on open invoices and bills.");
        }

        // Allocate both sides FIFO with the shared allocator — the same arithmetic Receive Payment and Pay Vendor use.
        List<PaymentAllocationRef> custAlloc = subledgerService.allocate(invoiceDocs(invoices), amount);
        List<PaymentAllocationRef> vendAlloc = subledgerService.allocate(billDocs(bills), amount);
        customerService.recomputeDue(c);
        venderService.recomputePayable(venderId);

        String setOffNo = String.format("SETOFF-%06d", documentNumberService.next(org, DocType.SETOFF));
        String ref = blank(reference) ? setOffNo : setOffNo + " · " + reference.trim();

        // Both legs, one finance transaction. A failure throws and rolls everything above back.
        SetOffLedgerClient.Result posted = ledger.record(SetOffLedgerClient.Request.builder()
                .idempotencyKey(idempotencyKey.trim()).setOffNo(setOffNo)
                .customerId(customerId).customerName(c.getName()).venderId(venderId).venderName(v.getName())
                .amount(amount).paidOn(today).reference(ref)
                .customerAllocations(custAlloc).vendorAllocations(vendAlloc).build());
        if (posted == null) throw new IllegalStateException("finance-service did not confirm the set-off");

        PartySetOff s = new PartySetOff();
        s.setOrganizationId(org);
        s.setUserId(user());
        s.setSetOffNo(setOffNo);
        s.setPartyId(c.getPartyId());
        s.setCustomerId(customerId);
        s.setVenderId(venderId);
        s.setAmount(amount);
        s.setReason(reason.trim());
        s.setReference(blank(reference) ? null : reference.trim());
        s.setReceiptNo(posted.getReceiptNo());
        s.setVoucherNo(posted.getVoucherNo());
        s.setIdempotencyKey(idempotencyKey.trim());
        s.setStatus(PartySetOff.POSTED);
        s.setCreatedAt(LocalDateTime.now());
        s = setOffRepo.save(s);
        saveAllocs(s.getId(), PartySetOffAlloc.CUSTOMER, custAlloc);
        saveAllocs(s.getId(), PartySetOffAlloc.VENDOR, vendAlloc);

        auditService.record("SETOFF", "PARTY", setOffNo, amount,
                "customer=" + c.getName() + " supplier=" + v.getName() + " reason=" + reason.trim());
        return outcome(s, false);
    }

    // ---- reverse -----------------------------------------------------------------------------------------------------

    /** Undo a set-off: re-open exactly the invoices and bills it cleared, and mirror both journals in finance. */
    @Transactional
    public Outcome reverse(Long setOffId, String reason, String reversalKey) {
        Long org = org();
        if (blank(reversalKey)) throw new PartyRoleService.Refusal("This form is out of date. Reload the page and try again.");
        var prior = setOffRepo.findByReversalKey(org, reversalKey.trim());
        if (prior.isPresent()) return outcome(prior.get(), true);
        if (blank(reason)) throw new PartyRoleService.Refusal("Give a reason for reversing the set-off.");

        PartySetOff s = setOffRepo.findScoped(setOffId, org).orElseThrow(() -> new PartyRoleService.Refusal("Set-off not found."));
        if (!PartySetOff.POSTED.equals(s.getStatus())) throw new PartyRoleService.Refusal(s.getSetOffNo() + " is already reversed.");
        LocalDate today = LocalDate.now();
        periodLockGuard.assertOpen(today);
        if (ledger == null) throw new PartyRoleService.Refusal("Set-off is not available in this installation.");

        for (PartySetOffAlloc a : allocRepo.findBySetOff(s.getId())) reopen(a);
        customerRepo.findByIdScoped(s.getCustomerId(), org, user()).ifPresent(customerService::recomputeDue);
        venderService.recomputePayable(s.getVenderId());

        SetOffLedgerClient.Result posted = ledger.reverse(SetOffLedgerClient.ReverseRequest.builder()
                .idempotencyKey(s.getIdempotencyKey()).reversalKey(reversalKey.trim()).setOffNo(s.getSetOffNo())
                .reason(reason.trim()).reversedOn(today).build());
        if (posted == null) throw new IllegalStateException("finance-service did not confirm the reversal");

        s.setStatus(PartySetOff.REVERSED);
        s.setReversedBy(user());
        s.setReversedAt(LocalDateTime.now());
        s.setReversalReason(reason.trim());
        s.setReversalKey(reversalKey.trim());
        setOffRepo.save(s);
        auditService.record("SETOFF_REVERSED", "PARTY", s.getSetOffNo(), s.getAmount(), "reason=" + reason.trim());
        return outcome(s, false);
    }

    /** Put back exactly what the set-off took off this document: paid −= amount, due −= amount (away from zero). */
    private void reopen(PartySetOffAlloc a) {
        BigDecimal amt = nz(a.getAmount());
        if (PartySetOffAlloc.CUSTOMER.equals(a.getSide())) {
            CustomerHistory inv = customerHistoryRepo.findById(a.getDocId())
                    .orElseThrow(() -> new IllegalStateException("invoice " + a.getDocNo() + " is gone; cannot reverse"));
            inv.setPaidAmount(nz(inv.getPaidAmount()).subtract(amt));
            inv.setDueAmount(nz(inv.getDueAmount()).subtract(amt));
            inv.setUpdated(LocalDateTime.now());
            customerHistoryRepo.save(inv);
        } else {
            Purchase bill = purchaseRepo.findById(a.getDocId())
                    .orElseThrow(() -> new IllegalStateException("bill " + a.getDocNo() + " is gone; cannot reverse"));
            bill.setPaidAmount(nz(bill.getPaidAmount()).subtract(amt));
            bill.setDueAmount(nz(bill.getDueAmount()).subtract(amt));
            bill.setUpdated(LocalDateTime.now());
            purchaseRepo.save(bill);
        }
    }

    /** The set-offs recorded for one partner, newest first — what the 360 view lists with its Reverse buttons. */
    @Transactional(readOnly = true)
    public List<PartySetOff> forParty(Long partyId) {
        return partyId == null ? List.of() : setOffRepo.findByParty(org(), partyId);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static BigDecimal sum(List<BigDecimal> xs) {
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<OpenDoc> invoiceDocs(List<CustomerHistory> invoices) {
        List<OpenDoc> docs = new ArrayList<>();
        for (CustomerHistory inv : invoices) {
            docs.add(new OpenDoc() {
                public BigDecimal outstanding() { return nz(inv.getDueAmount()).negate(); }
                public void apply(BigDecimal applied) {
                    inv.setPaidAmount(nz(inv.getPaidAmount()).add(applied));
                    inv.setDueAmount(nz(inv.getDueAmount()).add(applied));   // moves toward 0
                    inv.setUpdated(LocalDateTime.now());
                    customerHistoryRepo.save(inv);
                }
                public String docType() { return "INVOICE"; }
                public Long docId() { return inv.getCustomer_history_id(); }
                public String docNo() { return inv.getInvoiceNo(); }
            });
        }
        return docs;
    }

    private List<OpenDoc> billDocs(List<Purchase> bills) {
        List<OpenDoc> docs = new ArrayList<>();
        for (Purchase bill : bills) {
            docs.add(new OpenDoc() {
                public BigDecimal outstanding() { return nz(bill.getDueAmount()).negate(); }
                public void apply(BigDecimal applied) {
                    bill.setPaidAmount(nz(bill.getPaidAmount()).add(applied));
                    bill.setDueAmount(nz(bill.getDueAmount()).add(applied));
                    bill.setUpdated(LocalDateTime.now());
                    purchaseRepo.save(bill);
                }
                public String docType() { return "PURCHASE"; }
                public Long docId() { return bill.getPurchaseId(); }
                public String docNo() { return bill.getPurchaseInvoiceNo(); }
            });
        }
        return docs;
    }

    private void saveAllocs(Long setOffId, String side, List<PaymentAllocationRef> allocs) {
        for (PaymentAllocationRef r : allocs) {
            PartySetOffAlloc a = new PartySetOffAlloc();
            a.setSetOffId(setOffId);
            a.setSide(side);
            a.setDocType(r.getDocType());
            a.setDocId(r.getDocId());
            a.setDocNo(r.getDocNo());
            a.setAmount(r.getAmount());
            allocRepo.save(a);
        }
    }

    private static Outcome outcome(PartySetOff s, boolean replay) {
        return new Outcome(s.getId(), s.getSetOffNo(), s.getAmount(), s.getReceiptNo(), s.getVoucherNo(), replay);
    }
}

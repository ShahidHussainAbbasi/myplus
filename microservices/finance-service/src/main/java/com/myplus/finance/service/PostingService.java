package com.myplus.finance.service;

import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.dto.JournalPostRequest;
import com.myplus.finance.dto.PostEventRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * F3b: turns economic events into balanced GL journals via the posting rules (accrual). finance-service OWNS the
 * accounting (which accounts to debit/credit); callers only report amounts. Posts through {@link GlService} by
 * account code against the org's (auto-seeded) chart of accounts. Every journal balances by construction:
 * SALE: Dr Cash(paid)+AR(rest) = grand = Cr Sales(sub)+Tax(tax);  PURCHASE: Dr Inventory = Cr Cash(paid)+AP(rest);
 * RECEIPT: Dr Cash = Cr AR;  PAYMENT: Dr AP = Cr Cash;  COGS: Dr COGS = Cr Inventory.
 */
@Service
@RequiredArgsConstructor
public class PostingService {

    private final GlService glService;

    // Default chart-of-accounts codes (see GlService.DEFAULT_COA).
    private static final String CASH = "1000", BANK = "1010", AR = "1100", INVENTORY = "1200",
            AP = "2000", TAX = "2100", SALES = "4000", COGS = "5000";

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static String cashAccount(String method) {
        if (method == null) return CASH;
        String m = method.trim().toUpperCase();
        return (m.startsWith("CARD") || m.startsWith("BANK") || m.startsWith("CHEQUE")) ? BANK : CASH;
    }

    private static JournalLineDTO dr(String code, BigDecimal amt) {
        return JournalLineDTO.builder().accountCode(code).debit(amt).build();
    }
    private static JournalLineDTO cr(String code, BigDecimal amt) {
        return JournalLineDTO.builder().accountCode(code).credit(amt).build();
    }

    // ---- Event posting (SALE/PURCHASE pushed by business-service) ----------------------------------------------

    @Transactional
    public void postEvent(PostEventRequest req) {
        glService.ensureDefaults();   // the org's chart of accounts must exist (idempotent)
        if ("SALE".equalsIgnoreCase(req.getEventType())) postSale(req);
        else if ("PURCHASE".equalsIgnoreCase(req.getEventType())) postPurchase(req);
        else throw new IllegalArgumentException("Unknown event type: " + req.getEventType());
    }

    private void postSale(PostEventRequest r) {
        BigDecimal grand = nz(r.getGrandTotal());
        if (grand.signum() <= 0) return;
        BigDecimal sub = nz(r.getSubTotal()), tax = nz(r.getTaxTotal());
        BigDecimal paid = nz(r.getPaidAmount()).max(BigDecimal.ZERO).min(grand);   // cap tender at the bill
        BigDecimal ar = grand.subtract(paid);
        List<JournalLineDTO> lines = new ArrayList<>();
        if (paid.signum() > 0) lines.add(dr(cashAccount(r.getMethod()), paid));
        if (ar.signum() > 0)   lines.add(dr(AR, ar));
        // Cr side = sub + tax = grand. If the caller didn't split sub/tax, credit the whole to Sales so it balances.
        if (sub.signum() > 0 || tax.signum() > 0) {
            if (sub.signum() > 0) lines.add(cr(SALES, sub));
            if (tax.signum() > 0) lines.add(cr(TAX, tax));
        } else {
            lines.add(cr(SALES, grand));
        }
        post("SALE", r.getDate(), r.getRef(), lines);

        BigDecimal cost = nz(r.getCost());   // COGS side (accrual, using the captured line cost)
        if (cost.signum() > 0) post("SALE", r.getDate(), r.getRef(), List.of(dr(COGS, cost), cr(INVENTORY, cost)));
    }

    private void postPurchase(PostEventRequest r) {
        BigDecimal total = nz(r.getGrandTotal());
        if (total.signum() <= 0) return;
        BigDecimal paid = nz(r.getPaidAmount()).max(BigDecimal.ZERO).min(total);
        BigDecimal ap = total.subtract(paid);
        List<JournalLineDTO> lines = new ArrayList<>();
        lines.add(dr(INVENTORY, total));
        if (paid.signum() > 0) lines.add(cr(cashAccount(r.getMethod()), paid));
        if (ap.signum() > 0)   lines.add(cr(AP, ap));
        post("PURCHASE", r.getDate(), r.getRef(), lines);
    }

    // ---- Payment posting (RECEIPT/DISBURSEMENT — from finance's own ledger) ------------------------------------

    /** Auto-post a receipt/disbursement recorded in the payment ledger. Best-effort caller (never fails the payment). */
    @Transactional
    public void postPayment(String direction, BigDecimal amount, String method) {
        BigDecimal amt = nz(amount);
        if (amt.signum() <= 0) return;
        glService.ensureDefaults();
        List<JournalLineDTO> lines = "DISBURSEMENT".equalsIgnoreCase(direction)
                ? List.of(dr(AP, amt), cr(cashAccount(method), amt))      // we pay a vendor
                : List.of(dr(cashAccount(method), amt), cr(AR, amt));     // a customer pays us
        String source = "DISBURSEMENT".equalsIgnoreCase(direction) ? "PAYMENT" : "RECEIPT";
        post(source, LocalDate.now(), null, lines);
    }

    private void post(String source, LocalDate date, String ref, List<JournalLineDTO> lines) {
        glService.postJournal(JournalPostRequest.builder()
                .entryDate(date != null ? date : LocalDate.now())
                .source(source).sourceRef(ref).memo(ref != null ? source + " " + ref : source)
                .lines(lines).build());
    }
}

package com.myplus.finance.service;

import com.myplus.common.security.CurrentUser;
import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.dto.JournalPostRequest;
import com.myplus.finance.dto.PostEventRequest;
import com.myplus.finance.entity.ProcessedEvent;
import com.myplus.finance.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
    private final ProcessedEventRepository processedEvents;   // Audit #5: idempotent GL posting

    // Default chart-of-accounts codes (see GlService.DEFAULT_COA).
    private static final String CASH = "1000", BANK = "1010", AR = "1100", INVENTORY = "1200",
            AP = "2000", TAX = "2100", STORE_CREDIT = "2200", SALES = "4000", COGS = "5000",
            // OB-1: the balancing side of an opening balance. NOT 3100 Retained Earnings — that is the
            // accumulation account, and writing to it directly would make a migration indistinguishable from
            // real prior profit. An opening balance IS the owner's stake as it stood on day one.
            OWNERS_EQUITY = "3000",
            FEE_INCOME = "4100",   // slice 0.1: education fee revenue, kept off 4000 Sales
            /**
             * B2B-P4b / D-4: CONTRA-REVENUE — trade discount given on an invoice.
             *
             * <p>A debit account that sits against Sales. Revenue is credited at the invoice's FACE VALUE and the
             * concession is debited here, so gross sales reconcile to the documents issued and "discount given"
             * is a single account balance. Netting the discount off revenue instead would destroy that number —
             * a shop that discounted heavily would look identical to one that simply sold less.
             */
            SALES_DISCOUNT = "4200",
            /**
             * Delivery charged to the customer.
             *
             * <p>Its own income line rather than more Sales: goods revenue and delivery revenue answer
             * different questions, and a shop that cannot separate them cannot tell whether its delivery
             * operation pays for itself. Credited from {@code grandTotal}, never from {@code subTotal}, so it
             * stays out of the goods revenue figure and out of the tax base.
             */
            DELIVERY_INCOME = "4300";

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

        // Audit #5: idempotent posting — a duplicate outbox delivery (same event_key) is a no-op. Claim the key in
        // this tx; a concurrent race hits the unique index → this tx rolls back → the outbox retries → the retry
        // finds the claim and skips. (A SALE posts two journals under one key, so we dedup the event, not the journal.)
        String eventKey = req.getEventKey();
        if (eventKey != null && !eventKey.isBlank()) {
            Long org = CurrentUser.organizationId();
            if (processedEvents.existsByOrganizationIdAndEventKey(org, eventKey)) return;   // already posted
            processedEvents.saveAndFlush(ProcessedEvent.builder()
                    .organizationId(org).eventKey(eventKey).createdAt(LocalDateTime.now()).build());
        }

        String type = req.getEventType();
        if ("SALE".equalsIgnoreCase(type)) postSale(req);
        else if ("PURCHASE".equalsIgnoreCase(type)) postPurchase(req);
        else if ("SALE_RETURN".equalsIgnoreCase(type)) postSaleReturn(req);
        else if ("PURCHASE_RETURN".equalsIgnoreCase(type)) postPurchaseReturn(req);
        else if ("FEE_CHARGE".equalsIgnoreCase(type)) postFeeCharge(req);
        else if ("FEE_CREDIT_ISSUED".equalsIgnoreCase(type)) postFeeCreditIssued(req);
        else if ("FEE_CREDIT_APPLIED".equalsIgnoreCase(type)) postFeeCreditApplied(req);
        else if ("OPENING_AR".equalsIgnoreCase(type)) postOpeningReceivable(req);
        else if ("OPENING_AP".equalsIgnoreCase(type)) postOpeningPayable(req);
        else throw new IllegalArgumentException("Unknown event type: " + type);
    }

    /**
     * OB-1 — what a CUSTOMER owed before this shop started using MaxTheService.
     *
     *     Dr 1100 Accounts Receivable      Cr 3000 Owner's Equity
     *
     * <h3>⚠ Never through 4000 Sales, and this is the point of the whole slice</h3>
     * An opening balance is not trade. Booking it through Sales would report last year's business as this
     * month's revenue, carry it into the tax register as output tax on a sale that never happened here, and
     * overstate the margin on a period the shop did not trade in. That is exactly what a shop does today
     * when it "migrates" by back-dating invoices, and it is what OB-1 exists to replace.
     *
     * <h3>No tax, no COGS, no inventory — deliberately</h3>
     * There are no lines and no goods: the money was owed, and whatever was sold to create it was sold in a
     * system that is not this one, and taxed there. Inventing any of those here would be inventing facts.
     *
     * <p>The DATE is the tenant's cutover, carried from the event. Until V60 the outbox dropped the date and
     * the relay stamped {@code LocalDate.now()}, which would have landed every migration in the current
     * period — see that migration for what else it was quietly doing to ordinary sales.
     */
    private void postOpeningReceivable(PostEventRequest r) {
        BigDecimal owed = nz(r.getGrandTotal());
        if (owed.signum() <= 0) return;   // nothing owed is nothing to post, not an error
        post("OPENING_AR", r.getDate(), r.getRef(), List.of(dr(AR, owed), cr(OWNERS_EQUITY, owed)));
    }

    /**
     * OB-1 — what this shop owed a SUPPLIER at cutover. The mirror.
     *
     *     Dr 3000 Owner's Equity           Cr 2000 Accounts Payable
     *
     * <p>An opening payable REDUCES the owner's stake: the business starts owing money it has not yet paid
     * for, so equity is debited rather than credited. Getting this backwards would balance the journal and
     * report the opposite of the truth about the shop's net worth — which is why the gate asserts the SIGNED
     * movement on both accounts rather than that the trial balance merely still balances.
     */
    private void postOpeningPayable(PostEventRequest r) {
        BigDecimal owed = nz(r.getGrandTotal());
        if (owed.signum() <= 0) return;
        post("OPENING_AP", r.getDate(), r.getRef(), List.of(dr(OWNERS_EQUITY, owed), cr(AP, owed)));
    }

    // Reverse a sale (goods back, refund/AR credited): mirror image of postSale. grand = returned value,
    // paidAmount = cash refunded (rest reduces AR), cost = COGS of the returned goods (Inventory restored).
    private void postSaleReturn(PostEventRequest r) {
        if (nz(r.getGrandTotal()).signum() <= 0) return;
        post("SALE_RETURN", r.getDate(), r.getRef(), saleReturnLines(r));
        BigDecimal cost = nz(r.getCost());
        if (cost.signum() > 0) post("SALE_RETURN", r.getDate(), r.getRef(), List.of(dr(INVENTORY, cost), cr(COGS, cost)));
    }

    /** The SALE_RETURN journal, built and nothing else — the mirror of {@link #saleLines} and, like it,
     *  extracted so the balance can be asserted without a ledger. */
    static List<JournalLineDTO> saleReturnLines(PostEventRequest r) {
        BigDecimal grand = nz(r.getGrandTotal());
        BigDecimal sub = nz(r.getSubTotal()), tax = nz(r.getTaxTotal());
        BigDecimal refund = nz(r.getPaidAmount()).max(BigDecimal.ZERO).min(grand);
        BigDecimal sc = nz(r.getStoreCredit()).max(BigDecimal.ZERO).min(refund);   // refund issued as store credit (⊆ refund)
        BigDecimal cash = refund.subtract(sc);                                     // the rest handed back as cash
        BigDecimal ar = grand.subtract(refund);
        // A VOID must reverse EVERY leg the sale posted, including the two whole-document ones. Omitting them
        // would leave 4200 Sales Discount holding a concession on a cancelled invoice and 4300 Delivery Income
        // holding a fee that was refunded — and, because delivery rides inside `grand`, the journal would not
        // balance at all. Mirror image of postSale:
        //     Dr Sales (sub + d) + Dr Tax tax + Dr Delivery ship  =  Cr Cash/SC/AR grand + Cr Discount d
        // A PARTIAL return (credit note) sends neither — a shop refunds the goods, not the delivery — so both
        // are zero there and the journal is byte-for-byte what it was.
        BigDecimal discount = nz(r.getDiscountTotal()).max(BigDecimal.ZERO);
        BigDecimal ship = nz(r.getShippingFee()).max(BigDecimal.ZERO);

        List<JournalLineDTO> lines = new ArrayList<>();
        if (sub.signum() > 0 || tax.signum() > 0) {
            if (sub.signum() > 0 || discount.signum() > 0) lines.add(dr(SALES, sub.add(discount)));
            if (tax.signum() > 0) lines.add(dr(TAX, tax));
        } else {
            lines.add(dr(SALES, grand.subtract(ship).add(discount)));
        }
        if (ship.signum() > 0)   lines.add(dr(DELIVERY_INCOME, ship));   // the delivery income is given back
        if (sc.signum() > 0)     lines.add(cr(STORE_CREDIT, sc));   // we now owe the customer store credit
        if (cash.signum() > 0)   lines.add(cr(cashAccount(r.getMethod()), cash));
        if (ar.signum() > 0)     lines.add(cr(AR, ar));
        if (discount.signum() > 0) lines.add(cr(SALES_DISCOUNT, discount));   // un-take the concession
        return lines;
    }

    // Reverse a purchase (goods back to vendor): mirror of postPurchase. grand = returned value, paidAmount = cash
    // refunded by the vendor (rest reduces AP), Inventory credited.
    private void postPurchaseReturn(PostEventRequest r) {
        BigDecimal value = nz(r.getGrandTotal());   // returned bill = returned goods + returned input tax
        if (value.signum() <= 0) return;
        BigDecimal tax = nz(r.getTaxTotal()).max(BigDecimal.ZERO).min(value);   // input tax reversed
        BigDecimal net = value.subtract(tax);
        BigDecimal refund = nz(r.getPaidAmount()).max(BigDecimal.ZERO).min(value);
        BigDecimal ap = value.subtract(refund);
        List<JournalLineDTO> lines = new ArrayList<>();
        if (refund.signum() > 0) lines.add(dr(cashAccount(r.getMethod()), refund));
        if (ap.signum() > 0)     lines.add(dr(AP, ap));
        lines.add(cr(INVENTORY, net));
        if (tax.signum() > 0)    lines.add(cr(TAX, tax));
        post("PURCHASE_RETURN", r.getDate(), r.getRef(), lines);
    }

    /**
     * Slice 0.2a — a school fee CHARGE (a monthly due raised): Dr 1100 AR = Cr 4100 Fee Income.
     *
     * This is the education analogue of {@link #postSale}'s AR leg, minus tax and COGS (tuition is generally not
     * taxable, and a service has no inventory cost). Revenue is recognised when the fee is CHARGED — the accrual
     * basis POS and Pharmacy already use — so a school's unpaid fees appear on the balance sheet as an asset.
     *
     * The payment side is deliberately NOT here. A fee receipt goes through the same path a customer receipt
     * does: {@code PaymentService.record()} → {@link #postPayment} → Dr Cash = Cr AR, allocated across open
     * documents by the shared SubledgerService. Slice 0.1's FEE_COLLECTION rule was REMOVED for exactly this
     * reason — under accrual it would recognise revenue a second time, and it was a third implementation of a
     * concept finance already had.
     */
    private void postFeeCharge(PostEventRequest r) {
        BigDecimal charged = nz(r.getGrandTotal());
        if (charged.signum() <= 0) return;   // nothing charged is not an accounting event
        post("FEE_CHARGE", r.getDate(), r.getRef(),
                List.of(dr(AR, charged), cr(FEE_INCOME, charged)));
    }

    /**
     * Slice 0.2b — a parent overpaid: the school received cash it does not yet own.
     *
     *     Dr Cash|Bank   =   Cr 2200 Store/Fee Credit
     *
     * The surplus is a LIABILITY, not income — the school is holding the parent's money until a future charge
     * consumes it. Account 2200 and this direction are exactly what POS uses when a return issues store credit
     * ({@code cr(STORE_CREDIT, sc)}), so both verticals report held customer money in one place.
     *
     * Only the SURPLUS comes here; the part of the tender that actually settled dues posts through the normal
     * receipt path (Dr Cash = Cr AR).
     */
    private void postFeeCreditIssued(PostEventRequest r) {
        BigDecimal surplus = nz(r.getGrandTotal());
        if (surplus.signum() <= 0) return;
        post("FEE_CREDIT_ISSUED", r.getDate(), r.getRef(),
                List.of(dr(cashAccount(r.getMethod()), surplus), cr(STORE_CREDIT, surplus)));
    }

    /**
     * Slice 0.2b — credit the school already held is spent against a fee.
     *
     *     Dr 2200 Store/Fee Credit   =   Cr 1100 AR
     *
     * No cash moves: the liability shrinks and the receivable clears. Posting this through the cash receipt path
     * instead would record the same money as received twice — once when the parent overpaid, once when the credit
     * was used. Mirrors POS's redeem leg, {@code dr(STORE_CREDIT, sc)}.
     */
    private void postFeeCreditApplied(PostEventRequest r) {
        BigDecimal used = nz(r.getGrandTotal());
        if (used.signum() <= 0) return;
        post("FEE_CREDIT_APPLIED", r.getDate(), r.getRef(),
                List.of(dr(STORE_CREDIT, used), cr(AR, used)));
    }

    private void postSale(PostEventRequest r) {
        if (nz(r.getGrandTotal()).signum() <= 0) return;
        post("SALE", r.getDate(), r.getRef(), saleLines(r));

        BigDecimal cost = nz(r.getCost());   // COGS side (accrual, using the captured line cost)
        if (cost.signum() > 0) post("SALE", r.getDate(), r.getRef(), List.of(dr(COGS, cost), cr(INVENTORY, cost)));
    }

    /**
     * The SALE journal, built and nothing else — no DB, no Spring, no side effects.
     *
     * <p>Extracted from {@link #postSale} so the property that actually matters can be asserted
     * directly: <b>it balances</b>. The arithmetic below has four interacting legs (tender split,
     * store credit, contra-revenue discount, delivery income) and a mistake in any of them produces a
     * lopsided journal that no unit test could previously see, because the only way to reach this code
     * was to post to a real ledger.
     */
    static List<JournalLineDTO> saleLines(PostEventRequest r) {
        BigDecimal grand = nz(r.getGrandTotal());
        BigDecimal sub = nz(r.getSubTotal()), tax = nz(r.getTaxTotal());
        BigDecimal paid = nz(r.getPaidAmount()).max(BigDecimal.ZERO).min(grand);   // cap tender at the bill
        BigDecimal sc = nz(r.getStoreCredit()).max(BigDecimal.ZERO).min(paid);     // store-credit redeemed (⊆ paid)
        BigDecimal cash = paid.subtract(sc);                                       // the rest of the tender is cash/card
        BigDecimal ar = grand.subtract(paid);
        // D-4: a whole-document trade discount is CONTRA-REVENUE, not a reduction of revenue. The customer owes
        // the discounted figure (that is `grand`), so the debit side is unchanged; instead Sales is grossed UP by
        // the discount and the concession debited to 4200.
        //
        // The caller's contract, which the balance below depends on:
        //   sub   = goods ex-tax, ALREADY NET of the discount
        //   grand = sub + tax + shipping   (i.e. also net of the discount)
        //   ship  = delivery, in `grand` but NOT in `sub` and NOT in `tax`
        // Then, writing L for the goods' list value (L = sub + d):
        //   Dr  grand + d  =  (sub + tax + ship) + d  =  L + tax + ship
        //   Cr  Sales L + Tax tax + Delivery ship     =  L + tax + ship      ✓
        // Zero discount and zero shipping leave the journal byte-for-byte what it was, which is every till sale.
        BigDecimal discount = nz(r.getDiscountTotal()).max(BigDecimal.ZERO);
        BigDecimal ship = nz(r.getShippingFee()).max(BigDecimal.ZERO);

        List<JournalLineDTO> lines = new ArrayList<>();
        if (sc.signum() > 0)   lines.add(dr(STORE_CREDIT, sc));   // reduce the liability we owed the customer
        if (cash.signum() > 0) lines.add(dr(cashAccount(r.getMethod()), cash));
        if (ar.signum() > 0)   lines.add(dr(AR, ar));
        if (discount.signum() > 0) lines.add(dr(SALES_DISCOUNT, discount));
        // Cr side = sub + tax + shipping = grand. If the caller didn't split sub/tax, credit the goods portion
        // (grand less the delivery it contains) to Sales so the journal still balances.
        if (sub.signum() > 0 || tax.signum() > 0) {
            if (sub.signum() > 0 || discount.signum() > 0) lines.add(cr(SALES, sub.add(discount)));
            if (tax.signum() > 0) lines.add(cr(TAX, tax));
        } else {
            lines.add(cr(SALES, grand.subtract(ship).add(discount)));
        }
        if (ship.signum() > 0) lines.add(cr(DELIVERY_INCOME, ship));
        return lines;
    }

    private void postPurchase(PostEventRequest r) {
        BigDecimal total = nz(r.getGrandTotal());   // bill = goods (net) + input tax
        if (total.signum() <= 0) return;
        BigDecimal tax = nz(r.getTaxTotal()).max(BigDecimal.ZERO).min(total);   // input tax (reclaimable) — 0 unless the org captures it
        BigDecimal net = total.subtract(tax);       // goods value → Inventory
        BigDecimal paid = nz(r.getPaidAmount()).max(BigDecimal.ZERO).min(total);
        BigDecimal ap = total.subtract(paid);
        List<JournalLineDTO> lines = new ArrayList<>();
        lines.add(dr(INVENTORY, net));
        if (tax.signum() > 0) lines.add(dr(TAX, tax));
        if (paid.signum() > 0) lines.add(cr(cashAccount(r.getMethod()), paid));
        if (ap.signum() > 0)   lines.add(cr(AP, ap));
        post("PURCHASE", r.getDate(), r.getRef(), lines);
    }

    // ---- Payment posting (RECEIPT/DISBURSEMENT — from finance's own ledger) ------------------------------------

    /** Auto-post a receipt/disbursement recorded in the payment ledger. Runs in the caller's (PaymentService.record)
     *  transaction, so the payment and its journal are atomic — a failure here rolls the payment back too. */
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

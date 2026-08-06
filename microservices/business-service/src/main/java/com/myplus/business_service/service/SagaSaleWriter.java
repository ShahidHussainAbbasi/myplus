package com.myplus.business_service.service;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The local DB writes of the sell saga (slice 33, U3b), in their own committed transactions
 * ({@code REQUIRES_NEW}) so the PENDING sale is durable BEFORE the orchestrator calls {@code confirm} on
 * inventory — and is independent of the caller's (legacy) transaction. Separate bean so the proxy applies
 * (no self-invocation).
 */
@Service
@RequiredArgsConstructor
@Slf4j   // B2B-P3b-2: batch traceability is best-effort, so its failures must be visible in the log
public class SagaSaleWriter {

    private final ICustomerService customerService;
    private final ICustomerHistoryService customerHistoryService;
    private final ISellService sellService;
    private final PaymentService paymentService;
    private final com.myplus.business_service.repository.CashierShiftRepo cashierShiftRepo;
    private final com.myplus.business_service.repository.SellBatchRepo sellBatchRepo;   // B2B-P3b-2 (#4)

    private static java.math.BigDecimal nz(java.math.BigDecimal v) { return v != null ? v : java.math.BigDecimal.ZERO; }

    /** Write Customer + invoice (PENDING, carrying the reservation) + Sell lines (productId, catalog rate). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CustomerHistory writePending(CustomerHistoryDTO dto, String reservationId, String idempotencyKey,
                                        AuthenticatedUser user, List<SagaLine> lines,
                                        java.util.List<com.myplus.commerce.contracts.dto.StockPick> picks) {
        dto.setUserId(user.getUserId());
        Customer customer;
        try {
            customer = customerService.saveUpdateCustomer(dto);   // declares checked Exception
        } catch (Exception e) {
            throw new RuntimeException("Failed to save customer for sale", e);
        }
        customerService.save(customer);

        CustomerHistory ch = customerHistoryService.saveUpdateCustomerHistory(dto);
        ch.setCustomer(customer);
        ch.setReservationId(reservationId);
        ch.setIdempotencyKey(idempotencyKey);
        ch.setSagaStatus("PENDING");
        // POS day-close (slice 39): stamp the cashier's open shift (if any) so the X/Z report can aggregate it.
        cashierShiftRepo.findFirstByOrganizationIdAndUserIdAndStatusOrderByOpenedAtDesc(
                user.getOrganizationId(), user.getUserId(), com.myplus.business_service.entity.ShiftStatus.OPEN)
                .ifPresent(shift -> ch.setShiftId(shift.getId()));
        // SF-1/SF-2: totals + settle + Sell lines + recomputeDue, via the ONE shared path (also used by updateSell).
        applyInvoice(ch, lines, dto, user, false, picks);
        return ch;
    }

    /**
     * SF-1/SF-2 — THE authoritative invoice apply, shared by new sales (writePending) and edits (updateSell):
     * recompute the tax summary (subTotal/taxTotal/grandTotal) from the authoritative lines, settle payment,
     * (re)write the Sell lines, and recompute the customer's running due. Runs in the caller's transaction.
     * <p>Settlement: {@code paid = existingPaid (edit only) + any new tender}, and {@code due = paid − grandTotal}
     * (negative while owing) — the server DERIVES due; the client-sent dueAmount is not trusted. For an edit the
     * cashier only adds NEW payment (the prior payment is kept), so it is never double-counted.
     */
    /**
     * Overload for callers with no reservation in hand (the edit path). An edit re-prices an existing
     * invoice; it does not re-pick stock, so there are no new batches to record and the rows written at
     * sale time stand.
     */
    public void applyInvoice(CustomerHistory ch, List<SagaLine> lines, CustomerHistoryDTO dto,
                             AuthenticatedUser user, boolean replaceLines) {
        applyInvoice(ch, lines, dto, user, replaceLines, null);
    }

    public void applyInvoice(CustomerHistory ch, List<SagaLine> lines, CustomerHistoryDTO dto,
                             AuthenticatedUser user, boolean replaceLines, java.util.List<com.myplus.commerce.contracts.dto.StockPick> picks) {
        java.math.BigDecimal subTotal = java.math.BigDecimal.ZERO, taxTotal = java.math.BigDecimal.ZERO,
                grandTotal = java.math.BigDecimal.ZERO;
        for (SagaLine l : lines) {
            java.math.BigDecimal lineTax = nz(l.taxAmount());
            java.math.BigDecimal lineGross = nz(l.lineGross());
            subTotal = subTotal.add(lineGross.subtract(lineTax));
            taxTotal = taxTotal.add(lineTax);
            grandTotal = grandTotal.add(lineGross);
        }
        ch.setSubTotal(subTotal);
        ch.setTaxTotal(taxTotal);
        ch.setGrandTotal(grandTotal);
        // Stamp the store on a NEW invoice only; an edit keeps the store it was raised at, even if the editor's
        // active store differs (re-homing a sale to another store would silently move the money between them).
        if (!replaceLines && ch.getStoreId() == null) ch.setStoreId(user.getActiveLocationId());

        // B2B-P3g: the invoice-level trade discount, as submitted. Distinct from the per-line discounts
        // above — a distribution invoice settles a whole-order concession at the foot of the document.
        if (dto.getTradeDiscount() != null) ch.setTradeDiscount(dto.getTradeDiscount());

        // B2B-P4b: the buyer's PO reference, carried from the quote. Only set when supplied, so a till sale
        // (which has no PO) is unaffected and an edit that omits it cannot blank an issued invoice's reference.
        if (dto.getCustomerPoNumber() != null && !dto.getCustomerPoNumber().isBlank())
            ch.setCustomerPoNumber(dto.getCustomerPoNumber().trim());

        // B2B-P3g: STAMP who booked the order, on a new invoice only. Stamped rather than joined on userId at
        // print time for two reasons: a print must not depend on auth-service being up, and an issued
        // document must not start showing a person's new name after they are renamed. Same rule as the
        // balanceAfter snapshot below. An edit deliberately keeps the ORIGINAL booker — the person who took
        // the order is a fact about the order, not about who last touched the row.
        //
        // The stamped value is the operator's EMAIL, because that is the only identity the gateway puts on
        // AuthenticatedUser — there is no display name in the token. That is honest rather than ideal: a
        // person's name would have to come from auth-service, which is precisely the print-path dependency
        // this stamp exists to avoid. If a proper display name is wanted on the document, the right fix is
        // to add it to the JWT claims so it arrives with the request, not to look it up while printing.
        if (!replaceLines && ch.getBookedByName() == null && user.getEmail() != null) {
            ch.setBookedByName(user.getEmail());
        }

        // Settle: an edit KEEPS the invoice's prior payment and ADDS any new tender; a new sale starts at 0.
        java.math.BigDecimal existingPaid = replaceLines ? nz(ch.getPaidAmount()) : java.math.BigDecimal.ZERO;
        boolean hasTenders = dto.getTenders() != null && !dto.getTenders().isEmpty();
        java.math.BigDecimal paid = existingPaid;
        if (hasTenders) {
            // Settle the NEW tender against what's still owed (grandTotal − alreadyPaid), so an edit's extra
            // payment is capped at the remaining balance and never double-counts the prior payment. For a new
            // sale existingPaid = 0, so this is simply the whole grand total.
            java.math.BigDecimal remaining = grandTotal.subtract(existingPaid);
            if (remaining.compareTo(java.math.BigDecimal.ZERO) < 0) remaining = java.math.BigDecimal.ZERO;
            SettleResult st = PaymentService.settle(remaining, dto.getTenders());
            ch.setPaymentMode(st.paymentMode());
            ch.setTenderedAmount(st.tendered());
            ch.setChangeAmount(st.change());
            paid = existingPaid.add(st.paid());
        }
        ch.setPaidAmount(paid);
        ch.setDueAmount(paid.subtract(grandTotal));   // negative while owing (recomputeDue convention)
        customerHistoryService.save(ch);

        if (hasTenders) {
            paymentService.record(ch.getCustomer_history_id(), dto.getTenders(),
                    user.getOrganizationId(), user.getUserId());
        }

        // (Re)write the Sell lines authoritatively (discount + catalog snapshot + tax + sold rate).
        if (replaceLines) {
            for (Sell o : sellService.findByInvoiceScoped(ch.getCustomer_history_id(),
                    user.getOrganizationId(), user.getUserId())) {
                sellService.deleteById(o.getSellId());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (SagaLine l : lines) {
            Sell sell = new Sell();
            sell.setUserId(user.getUserId());
            sell.setOrganizationId(user.getOrganizationId());
            sell.setStoreId(user.getActiveLocationId());   // multi-location: the store this sale happened at (null = single-store)
            sell.setProductId(l.productId());        // saga sell: catalog product, no local Stock FK
            sell.setQuantity(l.quantity());
            sell.setSellRate(l.sellRate());          // the ACTUAL sold rate (cashier's rate; falls back to catalog)
            sell.setCatalogPrice(l.catalogPrice());  // catalog master price snapshot at sale time (for reports)
            sell.setPriceReason(l.priceReason());    // B2B-P2 (#10): why that price applied — snapshotted
                                                     // so the invoice still explains itself if the rule changes
            sell.setCostPrice(l.costPrice());        // SF-10: unit COGS snapshot → per-line margin in the report
            sell.setDiscount(l.discount());
            sell.setBonusQuantity(l.bonusQuantity()); // B2B-P3g: free goods on this line ("Bon." on a trade
                                                      // invoice). Printed, not priced — see decision D-2.
            sell.setDt(l.discountType());            // persist the discount type ("%" / "Amount") for the sell history table
            sell.setTotalAmount(l.totalAmount());
            sell.setNetAmount(l.netAmount());
            sell.setSrp(l.srp());
            sell.setTaxRate(l.taxRate());          // G3: applied tax rate + amount per line
            sell.setTaxAmount(l.taxAmount());
            sell.setCustomerHistory(ch);
            sell.setDated(now);
            sell.setUpdated(now);
            sell = sellService.save(sell);
            recordBatches(sell, l, picks, user);
        }
        customerService.recomputeDue(ch.getCustomer());
        // B2B-P3b-2 (#4): snapshot what the customer owes AFTER this sale. Taken here because recomputeDue
        // has just run; Customer.dueAmount is the CURRENT balance, so reading it at PRINT time would put
        // today's figure on a two-year-old reprint.
        //
        // The explicit re-save matters: `ch` is built by ObjectMapperUtils.map (a DETACHED instance) and the
        // save above returns a managed copy the caller ignores. Setting a field on `ch` after that save is
        // therefore invisible to JPA -- exactly why the first version of this silently stored nothing.
        if (ch.getCustomer() != null) {
            ch.setBalanceAfter(ch.getCustomer().getDueAmount());
            customerHistoryService.save(ch);
        }
    }

    /**
     * Persist WHICH batches this line consumed, from the FEFO picks the reservation already returned.
     *
     * <p>Best-effort by design: a missing pick must never fail a recorded sale. The money and the stock
     * movement are the transaction; the traceability row is a record OF it. Losing a row is bad; losing the
     * sale is worse.
     *
     * <p>Picks are matched on product id, and a line split across several batches yields several rows --
     * which is the whole reason this is a child table rather than a column.
     */
    private void recordBatches(Sell sell, SagaLine line, java.util.List<com.myplus.commerce.contracts.dto.StockPick> picks, AuthenticatedUser user) {
        if (picks == null || picks.isEmpty() || sell == null || sell.getSellId() == null) return;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            for (com.myplus.commerce.contracts.dto.StockPick p : picks) {
                if (p == null || p.getItemId() == null || !p.getItemId().equals(line.productId())) continue;
                if (p.getBatchNo() == null && p.getExpiryDate() == null) continue;   // nothing to trace
                sellBatchRepo.save(com.myplus.business_service.entity.SellBatch.builder()
                        .sellId(sell.getSellId())
                        .organizationId(user.getOrganizationId())
                        .productId(p.getItemId())
                        .batchNo(p.getBatchNo())
                        .expiryDate(p.getExpiryDate())
                        .quantity(p.getQuantity())
                        .createdAt(now)
                        .build());
            }
        } catch (Exception e) {
            // WARN, not ERROR: the sale is safe. Kept loud enough to find, because this catch is exactly
            // what made a silent failure hard to see during P3b2.
            log.warn("Could not record batch traceability for sell {} (the sale itself is recorded)",
                    sell.getSellId(), e);
        }
    }

    /** Flip the invoice's saga status (PENDING → CONFIRMED/FAILED) in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStatus(Long customerHistoryId, String status) {
        customerHistoryService.findById(customerHistoryId).ifPresent(ch -> {
            ch.setSagaStatus(status);
            customerHistoryService.save(ch);
        });
    }
}

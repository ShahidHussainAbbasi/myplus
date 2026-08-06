package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * F3b: an economic event pushed to finance-service to auto-post a balanced GL journal (accrual). finance-service
 * owns the posting rules (which accounts to debit/credit); the caller (business-service) only reports the amounts.
 * SALE: grand = sub + tax; {@code paidAmount} = tendered at sale (rest → AR); {@code cost} = COGS.
 * PURCHASE: {@code grandTotal} = the bill; {@code paidAmount} = paid now (rest → AP).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PostingEventRequest {
    private String eventType;       // SALE | PURCHASE
    private String eventKey;        // Audit #5: unique per-event id → finance dedups a duplicate delivery (idempotent GL)
    private LocalDate date;
    private String ref;             // invoiceNo / purchaseInvoiceNo
    private BigDecimal grandTotal;
    private BigDecimal subTotal;    // ex-tax net (SALE)
    private BigDecimal taxTotal;
    private BigDecimal cost;        // COGS (SALE) — Σ(line cost × qty)
    private BigDecimal paidAmount;  // tendered at sale / paid at purchase
    private String method;          // CASH | CARD | BANK | CHEQUE (→ cash vs bank account)
    /**
     * B2B-P4b / D-4: the whole-document TRADE DISCOUNT given on this sale.
     *
     * <p>Posts to a CONTRA-REVENUE account (Dr Sales Discount), NOT netted off revenue. Revenue is credited at
     * the invoice's FACE VALUE, so gross sales reconcile to the documents issued and "discount given" is one
     * account balance rather than an invisible reduction. Netting it into revenue destroys the number — you can
     * no longer tell a shop that discounted heavily from one that simply sold less.
     *
     * <p>Null/zero on every sale that gave no document-level concession, which is the vast majority, so the
     * journal is unchanged for them.
     */
    private BigDecimal discountTotal;

    private BigDecimal storeCredit; // store credit portion: on SALE = redeemed (Dr 2200 not Cash); on SALE_RETURN =
                                    // issued (Cr 2200 not Cash). Null/0 = the classic cash posting (no regression).
}

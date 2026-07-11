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
}

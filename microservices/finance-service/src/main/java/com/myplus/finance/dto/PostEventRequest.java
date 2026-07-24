package com.myplus.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * F3b: the SALE/PURCHASE event finance-service receives (from business-service's FinanceClient.postEvent) to
 * auto-post a GL journal. Local mirror of commerce-contracts PostingEventRequest (field names match → JSON binds).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PostEventRequest {
    private String eventType;       // SALE | PURCHASE
    private String eventKey;        // Audit #5: unique per-event id → dedup a duplicate delivery (idempotent GL)
    private LocalDate date;
    private String ref;
    private BigDecimal grandTotal;
    private BigDecimal subTotal;
    private BigDecimal taxTotal;
    private BigDecimal cost;
    private BigDecimal paidAmount;
    private String method;
    private BigDecimal storeCredit;   // SALE = redeemed (Dr 2200); SALE_RETURN = issued (Cr 2200); null/0 = cash posting
}

package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Record-a-payment request sent to finance-service (the shared ledger). {@code partyType}/{@code direction} are
 * plain strings ("CUSTOMER", "RECEIPT") so callers need no finance enums; finance-service binds them to its enums.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentRecordRequest {
    private String direction;       // RECEIPT (default) | DISBURSEMENT
    private String partyType;       // CUSTOMER | VENDOR | ...
    private Long partyId;
    private String partyName;
    private BigDecimal amount;
    private String method;
    private LocalDate paidOn;
    private String reference;
    /** FP-5a — the caller's reference for this settlement: finance returns the FIRST payment for a repeat. */
    private String clientRef;
    private String sourceModule;    // BUSINESS | EDUCATION | ...
    private String note;
    private String purpose;         // EX-7b — ADVANCE: an advance to / back from a member (1300); null otherwise
    private List<PaymentAllocationRef> allocations;
}

package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FP-5b — part of a Pay Supplier payment applied to an expense bill. The money is ALREADY in finance's ledger (one
 * payment for the whole Pay Supplier, {@code clientRef}); this only tells the bill it has been paid. Idempotent on
 * (clientRef, bill), so a redelivery applies once.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class BillApplyRequest {
    private BigDecimal amount;
    private String clientRef;
    private String method;
    private LocalDate paidOn;
}

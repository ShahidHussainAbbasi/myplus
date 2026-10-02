package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FP-1/2 — one supplier payable as its SOURCE sees it right now (a snapshot, not a delta): what was billed and
 * what has been paid. finance-service stores it keyed by (organization, source, sourceRef) and ignores a snapshot
 * whose {@code sourceVersion} is older than the one it holds, so a late redelivery never overwrites newer figures.
 *
 * <p>source: PURCHASE (a supplier purchase line) today; EXPENSE_BILL arrives with EX-4 (FP-3).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class PayableSnapshot {
    private String source;
    private String sourceRef;
    private Long sourceVersion;
    private String partyType;      // VENDOR
    private Long partyId;
    private String partyName;
    private String docNo;
    private LocalDate docDate;
    private BigDecimal amount;
    private BigDecimal paid;
    private boolean voided;
}

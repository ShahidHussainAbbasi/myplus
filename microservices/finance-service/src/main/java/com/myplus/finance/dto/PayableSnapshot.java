package com.myplus.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FP-1 — local mirror of commerce-contracts {@code PayableSnapshot} (field names match → the JSON binds), the same
 * convention as {@link PostEventRequest}: finance takes no dependency on the contracts library.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class PayableSnapshot {
    private String source;
    private String sourceRef;
    private Long sourceVersion;
    private String partyType;
    private Long partyId;
    private String partyName;
    private String docNo;
    private LocalDate docDate;
    private BigDecimal amount;
    private BigDecimal paid;
    private boolean voided;
}

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
    /** FP-4a — the bill AS ISSUED (gross, before any return): the statement's BILL line. Null = use amount. */
    private BigDecimal issuedAmount;
    /** FP-4a — when the supplier expects payment (expense bills); null = age by the document date. */
    private LocalDate dueDate;
    /**
     * FP-4a — the debit notes against this document, for the statement trail. NULL = "not sent" (an older sender):
     * finance keeps what it has. EMPTY = "there are none". Never read by a balance — amount/paid carry those.
     */
    private java.util.List<PayableNote> notes;
}

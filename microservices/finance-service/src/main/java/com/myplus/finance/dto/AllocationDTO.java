package com.myplus.finance.dto;

import lombok.*;

import java.math.BigDecimal;

/** One allocation line — how much of a payment settles a given source document (invoice). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllocationDTO {
    private String docType;   // INVOICE
    private Long docId;       // CustomerHistory id
    private String docNo;     // invoice number
    private BigDecimal amount;
}

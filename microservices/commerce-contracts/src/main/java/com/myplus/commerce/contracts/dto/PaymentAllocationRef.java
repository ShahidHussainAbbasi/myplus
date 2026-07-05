package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One allocation line sent to finance-service: how much of a payment settles a source document (invoice). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentAllocationRef {
    private String docType;   // INVOICE
    private Long docId;       // CustomerHistory id
    private String docNo;     // invoice number
    private BigDecimal amount;
}

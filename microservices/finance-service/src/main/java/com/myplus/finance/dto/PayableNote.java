package com.myplus.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** FP-4a — one debit note against a payable document, as its statement shows it (a credit line). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class PayableNote {
    private String noteNo;
    private LocalDate noteDate;
    private BigDecimal amount;
}

package com.myplus.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** F3 (GL): post a balanced journal — the service enforces ≥2 lines and Σdebit = Σcredit. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class JournalPostRequest {
    private LocalDate entryDate;   // defaults to today if null
    private String source;         // SALE | PURCHASE | RECEIPT | PAYMENT | MANUAL (default MANUAL)
    private String sourceRef;
    private String memo;
    private List<JournalLineDTO> lines;
}

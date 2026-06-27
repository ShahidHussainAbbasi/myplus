package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** List row for parked sales (POS R10, slice 40) — no cart payload, just the summary. */
@Data
@AllArgsConstructor
public class ParkedSaleSummaryDTO {
    private Long id;
    private String label;
    private Integer itemCount;
    private BigDecimal total;
    private LocalDateTime parkedAt;
}

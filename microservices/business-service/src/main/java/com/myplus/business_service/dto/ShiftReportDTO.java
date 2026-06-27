package com.myplus.business_service.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** X/Z report for a cashier shift (POS day-close, slice 39). */
@Data
public class ShiftReportDTO {
    private Long shiftId;
    private String status;             // OPEN (X report) | CLOSED (Z report)
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private BigDecimal openingFloat;

    private long salesCount;
    private BigDecimal salesGross;     // Σ grand total
    private BigDecimal taxTotal;       // Σ tax

    private Map<String, BigDecimal> byMethod = new LinkedHashMap<>();  // CASH/CARD/.../REFUND → Σ amount

    private BigDecimal payIns;
    private BigDecimal payOuts;
    private BigDecimal drops;

    private BigDecimal cashSales;      // byMethod[CASH]
    private BigDecimal refunds;        // byMethod[REFUND] (negative)
    private BigDecimal expectedCash;
    private BigDecimal countedCash;    // Z only
    private BigDecimal variance;       // Z only (counted − expected)
}

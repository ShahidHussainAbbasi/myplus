package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One batch that a sale line drew from (slice b2b-P3b-2 = requirement #4) — the receipt's traceability row.
 *
 * <p>A LIST per line, not one value: FEFO splits a line across batches when the oldest cannot cover the
 * quantity, and a receipt that showed only the first would be wrong precisely during a recall.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SellBatchDTO {
    private String batchNo;
    private LocalDate expiryDate;
    private BigDecimal quantity;

    /** #17 P3: what one unit of this batch cost — the basis of the sale COGS. */
    private BigDecimal unitCost;
}

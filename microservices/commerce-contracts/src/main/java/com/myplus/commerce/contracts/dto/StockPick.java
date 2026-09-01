package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A FEFO pick the inventory service allocated for one reservation line: which batch, how much, and its
 * expiry — so the sale (and any pharmacy controlled-substance register) records exact batch traceability.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockPick {
    private Long itemId;
    private String batchNo;
    private BigDecimal quantity;
    private LocalDate expiryDate;

    /**
     * #17 P3 — what ONE unit of this batch cost.
     *
     * <p>Reported by the side that knows it, at the moment it is true. COGS is the cost of the goods that
     * actually left, and only inventory can say which batch left and what was paid for it — so the pick
     * carries the answer rather than the sale guessing with a proxy rate later.
     *
     * <p>Derived from the batch total where there is one (post-P2 batches, where a supplier bonus may have
     * made cost differ from the headline rate), else the batch purchase price.
     */
    private BigDecimal unitCost;
}

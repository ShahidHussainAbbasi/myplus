package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole-basket pricing quote (slice b2b-P2 / requirement #10) — request and response envelope.
 *
 * <p>Whole-basket on purpose: one call per sale, never one per line. {@code buildLines} already calls the
 * catalog once per line, and a price call per line would double the per-line network cost of every sale.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceQuote {

    private Long customerId;
    /** Phase 0's Customer.customerType — WALK_IN / RETAILER / WHOLESALE / VIP. */
    private String customerType;
    private List<PriceQuoteLine> lines = new ArrayList<>();
}

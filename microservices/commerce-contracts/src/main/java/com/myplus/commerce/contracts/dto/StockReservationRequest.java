package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to reserve stock for a pending sale (step 1 of the sell↔stock saga, slice 33).
 *
 * <p>{@code idempotencyKey} is caller-generated and makes retries safe: inventory-service returns the same
 * reservation for a repeated key instead of double-holding. FEFO (first-expiry-first-out) picking is the
 * default for batch/expiry-tracked items.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockReservationRequest {
    private String idempotencyKey;
    private List<StockReservationLine> lines;
}

package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to return sold stock back to inventory (G2 inverse saga, slice 34). The reservationId travels in the
 * path; the body carries the per-product quantities being returned. inventory-service restores each product to
 * the sale's original batches (the reservation picks), falling back to a fresh batch when picks are unavailable.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockReturnRequest {
    private List<StockReturnLine> lines;
    /** P11 (slice 55): when true, returned stock is quarantined (restockable=false), not put back on the shelf. */
    private boolean quarantine;

    /** Back-compat 1-arg: a plain restock return (quarantine=false) — used by retail/e-commerce callers. */
    public StockReturnRequest(List<StockReturnLine> lines) {
        this.lines = lines;
        this.quarantine = false;
    }
}

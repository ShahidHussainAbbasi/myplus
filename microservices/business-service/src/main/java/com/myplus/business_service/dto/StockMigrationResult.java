package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of a Stock→inventory seed run (slice 33, U2b). */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockMigrationResult {
    private int itemsConsidered;
    private int stockSeeded;
}

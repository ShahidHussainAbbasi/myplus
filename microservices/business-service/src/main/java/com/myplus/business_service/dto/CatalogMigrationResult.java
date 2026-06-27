package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of an item→product migration run (slice 33, U2). */
@Data @NoArgsConstructor @AllArgsConstructor
public class CatalogMigrationResult {
    private int totalItems;
    private int migrated;
    private int alreadyMapped;
}

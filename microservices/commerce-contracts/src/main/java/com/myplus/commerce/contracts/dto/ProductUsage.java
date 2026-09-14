package com.myplus.commerce.contracts.dto;

import lombok.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PROD-DEL — how many of one service's rows still reference a product.
 *
 * <p>The answer a service gives catalog before a product may be deleted permanently. {@code counts} maps a label
 * the OWNER can read ("stock records", "sales") to a row count, and only non-zero entries are listed, so an
 * unused product answers an empty map.
 *
 * <p>Counts, never rows: the endpoint behind this tells catalog THAT a product is in use, not what the rows say.
 * Design: docs/slices/prod-del-product-permanent-delete.md §3.1.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductUsage {

    /** Label → rows still referencing the product. Insertion-ordered, so the refusal message reads stably. */
    @Builder.Default
    private Map<String, Long> counts = new LinkedHashMap<>();

    /** Every row this service holds against the product. Zero means this service does not block the delete. */
    public long total() {
        long t = 0;
        if (counts != null) for (Long v : counts.values()) if (v != null) t += v;
        return t;
    }
}

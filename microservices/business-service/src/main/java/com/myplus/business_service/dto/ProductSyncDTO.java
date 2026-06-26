package com.myplus.business_service.dto;

import lombok.Data;

/**
 * Master-sync payload (slice 53): a catalog Product to project into a bridged business {@code Item}. Sent by the
 * monolith after it registers/edits a Product, so the one product master surfaces in the itemId-based screens.
 */
@Data
public class ProductSyncDTO {
    private Long productId;
    private String name;
    private String sku;
    private String unit;
    private String description;
    private String category;
}

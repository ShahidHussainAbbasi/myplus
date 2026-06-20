package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Maps a source {@code clientRef} (business Item id) to the catalog {@code productId} it became (slice 33, U2). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductImportResult {
    private Long clientRef;
    private Long productId;
}

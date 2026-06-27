package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One line of a sale return (G2 inverse saga, slice 34): how much of a product is being returned to inventory.
 * Tenant (org) and actor travel as gateway/propagated headers, never in the body.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockReturnLine {
    private Long productId;
    private Float qty;
}

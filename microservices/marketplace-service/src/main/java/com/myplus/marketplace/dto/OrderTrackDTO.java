package com.myplus.marketplace.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Public order-tracking projection (slice 56) — the minimal, safe fields a guest sees when tracking an order by
 * ref + contact. Deliberately excludes shipping address, payment ref and reservation id.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class OrderTrackDTO {
    private Long ref;
    private String customerName;
    private String status;        // fulfilment status (NEW/PACKED/SHIPPED/DELIVERED/CANCELLED)
    private LocalDateTime placedAt;
    private BigDecimal total;
}

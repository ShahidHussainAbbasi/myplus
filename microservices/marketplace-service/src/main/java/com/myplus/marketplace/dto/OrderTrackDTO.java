package com.myplus.marketplace.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Public order-tracking projection (slice 56) — the minimal, safe fields a guest sees when tracking an order by
 * ref + contact. Deliberately excludes shipping address, payment ref and reservation id. slice 57 adds the
 * status {@link #events} timeline.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class OrderTrackDTO {
    /**
     * OMS-8: the order's own number ({@code SO-000123}) — what the shopper quotes back and what tracking now
     * resolves. Was the raw primary key, which was both guessable and meaningless to a customer.
     */
    private String ref;
    private String customerName;
    private String status;        // current fulfilment status (NEW/PACKED/SHIPPED/DELIVERED/CANCELLED)
    private LocalDateTime placedAt;
    private BigDecimal total;
    private List<Event> events;   // status timeline (slice 57)

    /** One step in the fulfilment timeline. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Event {
        private String status;
        private LocalDateTime at;
    }
}

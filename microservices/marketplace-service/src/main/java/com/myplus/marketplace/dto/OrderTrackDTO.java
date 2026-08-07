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

    /**
     * OMS O5b — the parcels this order went out in.
     *
     * <p>Without these a customer who received half an order sees only the word {@code PARTIALLY_SHIPPED},
     * which reads like a fault rather than "the rest is on its way". Carrier and tracking number are the two
     * things they can actually act on.
     *
     * <p>Deliberately narrower than the back-office view: no internal note, no line ids, no user id.
     */
    private List<Parcel> parcels;

    /** One step in the fulfilment timeline. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Event {
        private String status;
        private LocalDateTime at;
    }

    /** One dispatch, as the shopper sees it. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Parcel {
        private String ref;              // SHP-000045
        private String carrier;
        private String trackingNumber;
        private LocalDateTime shippedAt;
        private Integer itemCount;       // how many units travelled in this parcel
    }
}

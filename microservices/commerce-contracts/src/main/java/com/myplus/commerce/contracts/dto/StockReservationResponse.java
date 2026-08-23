package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of a reserve / confirm / release call in the sell↔stock saga (slice 33).
 * On RESERVED, {@code picks} carries the FEFO batch allocation; on OUT_OF_STOCK, {@code message} explains
 * which line failed and {@code picks} is empty (nothing held).
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockReservationResponse {
    private String reservationId;
    private ReservationStatus status;
    private List<StockPick> picks;
    private String message;

    /**
     * O7 D1c — when this hold lapses if nothing confirms it. Null when the tenant has expiry switched off.
     *
     * <p>Returned because it is the ONLY way a caller can tell an ORDER hold from a CHECKOUT one, and the
     * difference between three days and thirty minutes is the whole of D1c. A test asserting merely that
     * "stock is held" passes identically on a hold that will be swept before the van leaves.
     */
    private java.time.LocalDateTime expiresAt;

    /** Back-compatible: every pre-D1c caller built a response without an expiry. */
    public StockReservationResponse(String reservationId, ReservationStatus status,
                                    java.util.List<StockPick> picks, String message) {
        this(reservationId, status, picks, message, null);
    }
}

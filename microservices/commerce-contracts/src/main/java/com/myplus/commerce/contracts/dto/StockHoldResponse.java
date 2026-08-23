package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * OMS O7 D1c — the answer to "is this order's stock actually set aside?"
 *
 * <h3>Not an exception, for the same reason {@link PolicyCheckResponse} is not</h3>
 * Failing to hold stock does not stop an order being confirmed — see {@code held} below. The caller needs to
 * render one panel, not catch an exception type to say "confirmed, but…".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHoldResponse {

    /**
     * True when the goods are genuinely set aside.
     *
     * <p><b>False is not a refusal of the order.</b> The admin is the person entitled to decide whether to
     * promise goods the shop has not got — a distributor with a delivery due tomorrow may confirm against it
     * knowingly. What must never happen is the reverse: reporting a hold that was not taken, which would let
     * the shop believe the stock is safe while another order sells it.
     */
    private boolean held;

    /** inventory-service's own words when it could not hold — e.g. "only 7 sellable, 10 requested". */
    private String reason;

    /**
     * When this hold lapses if the order is never dispatched.
     *
     * <p>Worth returning rather than keeping private: it is the only way a caller — or a test — can tell an
     * ORDER hold from a CHECKOUT hold, and the difference between three days and thirty minutes is the whole
     * of D1c. A test that merely asserted "stock is held" would pass identically on a hold that will be swept
     * before the van leaves.
     */
    private LocalDateTime expiresAt;

    /** inventory's own id for the hold. Diagnostic — callers address the hold by their order-derived key. */
    private String reservationId;
}

package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D4 — one line coming back off an invoice: what the shop refused at the door.
 *
 * <p>Keyed by {@code productId} because that is the language both services share. The caller knows what left
 * its warehouse; {@code sell_id} is business-service's own line identity and is deliberately not exported —
 * the same boundary {@link SaleRecordRequest.Line} draws in the other direction.
 *
 * <p>{@code quantity} is a {@code Float} to match {@code Sell.quantity}: a distributor sells by weight as well
 * as by count, and a contract that could not express 1.5 kg coming back would quietly force it to 2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaleReturnLine {

    private Long productId;

    /** How much of that product is coming back. Must be ≤ what the invoice sold, which the receiver enforces. */
    private Float quantity;
}

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
}

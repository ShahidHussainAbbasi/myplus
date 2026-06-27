package com.myplus.inventory.controller;

import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.commerce.contracts.dto.StockReturnRequest;
import com.myplus.commerce.contracts.dto.StockReturnResponse;
import com.myplus.common.security.CurrentUser;
import com.myplus.inventory.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Stock reservation API (slice 33, Phase 6a) — the inventory side of the sell↔stock saga. Returns the raw
 * {@link StockReservationResponse} (not an ApiResponse envelope) so trade-service's {@code InventoryClient}
 * deserializes it directly. org/user come from the propagated gateway identity (CurrentUser).
 */
@RestController
@RequestMapping("/api/inventory/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public StockReservationResponse reserve(@RequestBody StockReservationRequest request) {
        return reservationService.reserve(request, CurrentUser.organizationId(), CurrentUser.userId());
    }

    @PostMapping("/{reservationId}/confirm")
    public StockReservationResponse confirm(@PathVariable String reservationId) {
        return reservationService.confirm(reservationId, CurrentUser.organizationId(), CurrentUser.userId());
    }

    @PostMapping("/{reservationId}/release")
    public StockReservationResponse release(@PathVariable String reservationId) {
        return reservationService.release(reservationId, CurrentUser.organizationId(), CurrentUser.userId());
    }

    /** G2 inverse saga (slice 34) — return sold stock back to inventory for a confirmed sale. */
    @PostMapping("/{reservationId}/return")
    public StockReturnResponse returnStock(@PathVariable String reservationId, @RequestBody StockReturnRequest request) {
        return reservationService.returnPicks(reservationId, request.getLines(), request.isQuarantine(),
                CurrentUser.organizationId(), CurrentUser.userId());
    }
}

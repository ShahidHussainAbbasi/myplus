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
    /** OMS O5a — the expiry sweep, exposed manually as well as on a schedule. */
    private final com.myplus.inventory.service.ExpiredReservationSweeper sweeper;

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

    /**
     * O7 D1c — release a hold by the key its owner gave it, not by our id.
     *
     * <p>Separate path from {@code /{reservationId}/release} on purpose: an order knows its own key and has
     * nowhere sensible to keep ours. 200 with an empty body when there was nothing to release — the expiry
     * sweeper may have got there first, and both outcomes mean the stock is free.
     */
    @PostMapping("/release-by-key")
    public StockReservationResponse releaseByKey(@RequestParam("key") String key) {
        return reservationService.releaseByKey(key, CurrentUser.organizationId(), CurrentUser.userId());
    }

    /** G2 inverse saga (slice 34) — return sold stock back to inventory for a confirmed sale. */
    @PostMapping("/{reservationId}/return")
    public StockReturnResponse returnStock(@PathVariable String reservationId, @RequestBody StockReturnRequest request) {
        return reservationService.returnPicks(reservationId, request.getLines(), request.isQuarantine(),
                CurrentUser.organizationId(), CurrentUser.userId());
    }

    /**
     * OMS O5a — free this tenant's expired stock holds now, instead of waiting for the next scheduled pass.
     *
     * <p>Exists for three reasons: an operator who has just fixed an outage wants their stock back immediately
     * rather than in five minutes; it makes the sweeper's behaviour observable instead of something that only
     * ever happens in a log; and the Cypress gate cannot wait on a scheduler.
     *
     * <p>Owner/admin — it moves stock back into sellable, which is the same class of action as a stock
     * adjustment. Scoped to the caller's own organisation: a leak in one tenant is not another's to clean up.
     */
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
    @PostMapping("/sweep")
    public com.myplus.common.web.ApiResponse<java.util.Map<String, Object>> sweep() {
        int freed = sweeper.sweepForOrg(java.time.LocalDateTime.now(),
                CurrentUser.organizationId(), CurrentUser.userId());
        return com.myplus.common.web.ApiResponse.success(
                java.util.Map.of("released", freed),
                freed == 0 ? "No expired stock holds to release." : ("Released " + freed + " expired stock hold(s)."));
    }
}

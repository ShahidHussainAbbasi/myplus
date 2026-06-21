package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * Declarative client for the inventory-service stock-reservation API — the three saga steps of the
 * sell↔stock flow (slice 33). The implementing proxy is built from a load-balanced {@code RestClient}
 * (base URL {@code lb://inventory-service}) in the consuming service in Phase 6; this module ships only the
 * contract so caller (trade/pharma) and provider (inventory) compile against one source of truth.
 *
 * <p>Idempotency is via {@link StockReservationRequest#getIdempotencyKey()}; org/actor propagate as headers.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface InventoryClient {

    /** Saga step 1 — hold stock (FEFO). Returns RESERVED + picks, or OUT_OF_STOCK (nothing held). */
    @PostExchange("/reservations")
    StockReservationResponse reserve(@RequestBody StockReservationRequest request);

    /** Saga step 3 — confirm a held reservation: stock is decremented. Idempotent on reservationId. */
    @PostExchange("/reservations/{reservationId}/confirm")
    StockReservationResponse confirm(@PathVariable String reservationId);

    /** Compensation — release a held reservation (sale failed/abandoned): held stock returns. Idempotent. */
    @PostExchange("/reservations/{reservationId}/release")
    StockReservationResponse release(@PathVariable String reservationId);

    /** Seed opening stock for migrated products (item→product, slice 33 U2b). Returns the number created. */
    @PostExchange("/stock/import")
    Integer importStock(@RequestBody List<StockImportLine> lines);
}

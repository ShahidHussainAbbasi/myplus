package com.myplus.inventory.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.inventory.repository.StockEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public storefront availability (slice 49 follow-up) — anonymous (gateway allow-lists {@code /api/inventory/public/};
 * SecurityConfig permits {@code /api/inventory/public/**}). The store is identified by {@code org} since there is no
 * JWT identity. Returns the sellable quantity per product so the storefront can disable / cap out-of-stock items
 * BEFORE checkout (the reservation in {@code ReservationService} remains the authoritative oversell guard).
 */
@RestController
@RequestMapping("/api/inventory/public")
@RequiredArgsConstructor
public class PublicStockController {

    private final StockEntryRepository stockEntryRepository;

    @GetMapping("/availability")
    public ApiResponse<Map<Long, Double>> availability(@RequestParam("org") Long org) {
        Map<Long, Double> out = new LinkedHashMap<>();
        /*
         * EXP-1 — TRUE here, deliberately, and this is the one place that does not read the capability.
         *
         * This endpoint is ANONYMOUS: there is no JWT, so there is no caps claim to read, and resolving the
         * capability from this service's own settings store would answer from data auth-service owns — it holds
         * all 61 org.cap/org.shape rows; inventory holds none. Rather than resolve it from the wrong place, the
         * storefront keeps excluding dated stock.
         *
         * That is the safe direction for a public shop window: it may offer LESS than the till would sell (a
         * customer sees an item as unavailable), never more. The opposite — offering stock the checkout then
         * refuses — is the failure this query's own comment exists to prevent. If a non-tracking tenant ever
         * needs its storefront to include dated stock, the fix is to put the capability in the availability
         * response's source of truth, not to guess it here.
         */
        for (Object[] row : stockEntryRepository.availableByOrg(org, LocalDate.now(), true)) {
            if (row[0] == null) continue;
            Long productId = ((Number) row[0]).longValue();
            double available = row[1] == null ? 0d : ((Number) row[1]).doubleValue();
            out.put(productId, Math.max(0d, available));
        }
        return ApiResponse.success(out);
    }
}

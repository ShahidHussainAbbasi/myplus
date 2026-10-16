package com.myplus.pharma.config;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * HC-1d — the two calls a prescription from a LINKED clinic needs. Local to pharma-service (no other service needs these
 * views), and answered as plain maps: only the few fields used are read, so a field added on the far side changes nothing.
 */
public final class LinkedRxClients {

    private LinkedRxClients() {}

    /** auth-service {@code InternalLinkController}: the caller's route — {route, pharmacyOrgId, pharmacyName, clinicName}. */
    @HttpExchange(accept = "application/json")
    public interface Route {
        @GetExchange("/route")
        Map<String, Object> route();
    }

    /** catalog-service {@code GET /products/picker} — the org's sellable products, lean rows, paged. */
    @HttpExchange(accept = "application/json")
    public interface Medicines {
        @GetExchange("/products/picker")
        Map<String, Object> picker(@RequestParam("page") int page, @RequestParam("size") int size);
    }
}

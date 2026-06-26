package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.web.util.BusinessRestClient;
import com.web.util.CatalogRestClient;
import com.web.util.InventoryRestClient;

/**
 * Proxies the catalog-backed item picker (slice 33, U4.3 pre-stage). Additive — the new sell-screen picker
 * will load products from here ({@code /catalogProducts} → catalog-service {@code /products}) and submit a
 * productId. Nothing calls it yet, so it does not change the existing item flow.
 */
@RestController
public class CatalogController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private CatalogRestClient catalog;

    @Autowired
    private InventoryRestClient inventory;

    @Autowired
    private BusinessRestClient business;

    /** Catalog products for the picker. Pass-through of paging params, e.g. /catalogProducts?size=1000. */
    @GetMapping("/catalogProducts")
    @ResponseBody
    public Map<String, Object> products(final HttpServletRequest request) {
        try {
            return catalog.get("/products", request.getQueryString());
        } catch (Exception e) {
            LOGGER.error("catalogProducts proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** M1 (slice 42): register a catalog Product (the single product master). slice 53: also project a bridged
     *  business Item so the one master surfaces in the POS/pharmacy itemId screens (master-sync). */
    @PostMapping("/addProduct")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public Map<String, Object> addProduct(@RequestBody final Map<String, Object> body) {
        try {
            Map<String, Object> resp = catalog.postJson("/products", body);
            // master-sync (slice 53): best-effort — a sync failure must not fail the product registration.
            try {
                Object data = (resp != null) ? resp.get("data") : null;
                if (Boolean.TRUE.equals(resp != null ? resp.get("success") : null) && data instanceof Map<?, ?> p) {
                    Map<String, Object> sync = new java.util.HashMap<>();
                    sync.put("productId", ((Map<String, Object>) p).get("id"));
                    sync.put("name", body.get("name"));
                    sync.put("sku", body.get("sku"));
                    sync.put("unit", body.get("unit"));
                    sync.put("description", body.get("description"));
                    sync.put("category", body.get("categoryName"));
                    business.postJson("/syncProductItem", sync);
                }
            } catch (Exception sync) {
                LOGGER.warn("product->item master-sync failed (product was created): {}", sync.getMessage());
            }
            return resp;
        } catch (Exception e) {
            LOGGER.error("addProduct proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** M1 (slice 42): a single catalog Product by id. */
    @GetMapping("/getCatalogProduct")
    @ResponseBody
    public Map<String, Object> getCatalogProduct(final HttpServletRequest request) {
        try {
            return catalog.get("/products/" + request.getParameter("id"));
        } catch (Exception e) {
            LOGGER.error("getCatalogProduct proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** E7 (slice 49): stock a catalog product for the storefront — opening inventory the reservation saga draws
     *  down. Forwards a single opening-stock line to inventory {@code /stock/import} (org from the logged-in user). */
    @PostMapping("/addProductStock")
    @ResponseBody
    public Map<String, Object> addProductStock(@RequestBody final Map<String, Object> body) {
        try {
            Object productId = body.get("productId");
            Object quantity = body.get("quantity");
            // Optional lot info (slice 54, P10) — stock a specific batch/expiry so FEFO + the dispense screen show it.
            Map<String, Object> line = new java.util.HashMap<>();
            line.put("productId", productId);
            line.put("quantity", quantity);
            if (body.get("batchNo") != null) line.put("batchNo", body.get("batchNo"));
            if (body.get("expiryDate") != null) line.put("expiryDate", body.get("expiryDate"));
            String count = inventory.postJsonString("/stock/import", Collections.singletonList(line));
            return Map.of("success", true, "created", count);
        } catch (Exception e) {
            LOGGER.error("addProductStock proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** E7 (slice 49): current inventory on-hand for a product (for the storefront/back-office stock readout). */
    @GetMapping("/productStock")
    @ResponseBody
    public Map<String, Object> productStock(final HttpServletRequest request) {
        try {
            String level = inventory.getString("/stock/level/" + request.getParameter("productId"));
            return Map.of("success", true, "stock", level == null ? "0" : level.trim());
        } catch (Exception e) {
            LOGGER.error("productStock proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }
}

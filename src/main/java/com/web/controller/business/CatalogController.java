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
import org.springframework.web.client.HttpStatusCodeException;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private BusinessRestClient business;   // full productStock pre-fill (on-hand + price + FEFO batches)

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Turn a failed downstream call into a user-facing {success:false, message} body. When the catalog returned a
     * 4xx/5xx with a {message:...} body (e.g. "Product SKU already exists: 001"), relay that real reason instead of
     * a blank failure so the UI can tell the user what went wrong.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> failure(Exception e) {
        // The demo free-trial cap (403 DEMO_LIMIT) arrives as a DemoLimitException, which is NOT an
        // HttpStatusCodeException — so it used to fall straight through to the bare {success:false} below and the
        // "register at maxtheservice.com" upsell was lost. Worse, a capped write then looked like an unexplained
        // failure: two rounds of pharmacy test triage were spent on one. Let it reach DemoLimitAdvice, which
        // renders the upsell uniformly for every dashboard.
        if (e instanceof com.web.error.DemoLimitException dle) throw dle;

        if (e instanceof HttpStatusCodeException he) {
            try {
                Map<String, Object> body = objectMapper.readValue(he.getResponseBodyAsString(), Map.class);
                if (body.get("message") != null) {
                    return Map.of("success", false, "message", body.get("message"));
                }
            } catch (Exception ignore) {
                // fall through to the generic failure below
            }
        }
        return Collections.singletonMap("success", false);
    }

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

    /** M1 (slice 42): register a catalog Product — the single product master. M4e.d (slice 105): the legacy
     *  business Item projection (master-sync → /syncProductItem) is retired; the catalog Product is the only master. */
    @PostMapping("/addProduct")
    @ResponseBody
    public Map<String, Object> addProduct(@RequestBody final Map<String, Object> body) {
        try {
            return catalog.postJson("/products", body);
        } catch (Exception e) {
            LOGGER.error("addProduct proxy error", e);
            return failure(e);   // surface the real reason (e.g. duplicate SKU 409) to the user
        }
    }

    /**
     * Product list for the businessDashboard Product screen, shaped like the other {@code getUser*} endpoints so it
     * flows through the shared {@code loadDataTable()} path (same as Customer): a GenericResponse-style
     * {@code {status, collection:[...]}}. Only ACTIVE products are returned — a deactivated product drops off the
     * list (the "delete" UX), same as a deleted customer. Sourced from catalog {@code /products} (data.content).
     */
    @GetMapping("/getUserProduct")
    @ResponseBody
    public Map<String, Object> getUserProduct(final HttpServletRequest request) {
        try {
            // "Show inactive" toggle: when true, include deactivated products (each carries isActive so the row can
            // show a status badge + a Reactivate action). Default hides them (the "delete" UX).
            boolean includeInactive = "true".equalsIgnoreCase(request.getParameter("includeInactive"));
            Map<String, Object> resp = catalog.get("/products", "size=1000");
            java.util.List<Map<String, Object>> collection = new java.util.ArrayList<>();
            Object data = (resp != null) ? resp.get("data") : null;
            if (data instanceof Map<?, ?> page && page.get("content") instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> p)) continue;
                    boolean inactive = Boolean.FALSE.equals(p.get("isActive"));
                    if (inactive && !includeInactive) continue;   // deactivated → hidden unless "Show inactive"
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", p.get("id"));
                    row.put("name", p.get("name"));
                    row.put("sku", p.get("sku"));
                    row.put("unit", p.get("unit"));
                    row.put("sellingPrice", p.get("sellingPrice"));
                    row.put("taxRate", p.get("taxRate"));
                    row.put("categoryName", p.get("categoryName"));
                    row.put("manufacturer", p.get("manufacturer"));
                    row.put("description", p.get("description"));
                    row.put("isActive", p.get("isActive") == null ? Boolean.TRUE : p.get("isActive"));   // for the Status column / Reactivate
                    row.put("userId", p.get("createdBy"));   // keeps loadDataTable's userId bookkeeping happy
                    collection.add(row);
                }
            }
            Map<String, Object> out = new java.util.HashMap<>();
            out.put("status", collection.isEmpty() ? "NOT_FOUND" : "SUCCESS");
            out.put("collection", collection);
            return out;
        } catch (Exception e) {
            LOGGER.error("getUserProduct proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** Update a catalog Product (the "edit" Submit on the Product form) → catalog PUT /products/{id}. */
    @PostMapping("/updateProduct")
    @ResponseBody
    public Map<String, Object> updateProduct(@RequestBody final Map<String, Object> body) {
        try {
            return catalog.putJson("/products/" + body.get("id"), body);
        } catch (Exception e) {
            LOGGER.error("updateProduct proxy error", e);
            return failure(e);
        }
    }

    /** Barcode-first sell: resolve a scanned code (barcode or sku) to a ProductRef → catalog /products/lookup.
     *  A miss (404) or downstream hiccup returns {} so the sell screen shows "not found" without a scary error. */
    @GetMapping(value = "/lookupProduct", produces = "application/json")
    @ResponseBody
    public String lookupProduct(final HttpServletRequest request) {
        String code = request.getParameter("code");
        if (code == null || code.isBlank()) return "{}";
        try {
            return catalog.getString("/products/lookup?code="
                    + java.net.URLEncoder.encode(code.trim(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "{}";   // not found is normal on a mis-scan — no error log
        }
    }

    // ---- Multi-rate tax: tax-code master (catalog-service) proxies ----

    /** List the org's tax codes (JSON array) — for the Tax Codes screen + the product-form dropdown. */
    @GetMapping(value = "/catalogTaxCodes", produces = "application/json")
    @ResponseBody
    public String taxCodes() {
        try { return catalog.getString("/tax-codes"); }
        catch (Exception e) { LOGGER.error("catalogTaxCodes proxy error", e); return "[]"; }
    }

    /** Create (no id) or update (with id) a tax code → catalog POST/PUT /tax-codes. */
    @PostMapping("/saveTaxCode")
    @ResponseBody
    public Map<String, Object> saveTaxCode(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            return (id != null && !id.toString().isBlank())
                    ? catalog.putJson("/tax-codes/" + id.toString().trim(), body)
                    : catalog.postJson("/tax-codes", body);
        } catch (Exception e) { LOGGER.error("saveTaxCode proxy error", e); return failure(e); }
    }

    /** Delete a tax code → catalog DELETE /tax-codes/{id}. */
    @PostMapping("/deleteTaxCode")
    @ResponseBody
    public Map<String, Object> deleteTaxCode(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            if (id == null || id.toString().isBlank()) return Collections.singletonMap("success", false);
            catalog.delete("/tax-codes/" + id.toString().trim());
            return Collections.singletonMap("success", true);
        } catch (Exception e) { LOGGER.error("deleteTaxCode proxy error", e); return failure(e); }
    }

    /** Deactivate the checked products (the Product screen's Delete button) → catalog PUT /products/{id}/deactivate.
     *  Deactivate (not hard-delete) keeps products referenced by past sales/inventory intact; they drop off the list. */
    @PostMapping("/deactivateProduct")
    @ResponseBody
    public Map<String, Object> deactivateProduct(@RequestBody final Map<String, Object> body) {
        try {
            Object checked = body.get("checked");
            if (checked == null || checked.toString().isBlank()) return Collections.singletonMap("success", false);
            for (String id : checked.toString().split(",")) {
                if (!id.isBlank()) catalog.putJson("/products/" + id.trim() + "/deactivate", Collections.emptyMap());
            }
            return Collections.singletonMap("success", true);
        } catch (Exception e) {
            LOGGER.error("deactivateProduct proxy error", e);
            return failure(e);
        }
    }

    /** Reactivate a previously-deactivated product (the Product screen's Reactivate action) → catalog
     *  PUT /products/{id}/activate. Brings it back into the list + pickers. */
    @PostMapping("/activateProduct")
    @ResponseBody
    public Map<String, Object> activateProduct(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            if (id == null || id.toString().isBlank()) return Collections.singletonMap("success", false);
            catalog.putJson("/products/" + id.toString().trim() + "/activate", Collections.emptyMap());
            return Collections.singletonMap("success", true);
        } catch (Exception e) {
            LOGGER.error("activateProduct proxy error", e);
            return failure(e);
        }
    }

    /** Categories for the Product form's dropdown → catalog GET /categories (ApiResponse&lt;List&gt;). Returns a
     *  slim {@code {success, categories:[{id,name}]}} for the &lt;select&gt;. */
    @GetMapping("/getUserCategories")
    @ResponseBody
    public Map<String, Object> getUserCategories() {
        try {
            Map<String, Object> resp = catalog.get("/categories");
            java.util.List<Map<String, Object>> cats = new java.util.ArrayList<>();
            Object data = (resp != null) ? resp.get("data") : null;
            if (data instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> c)) continue;
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", c.get("id"));
                    row.put("name", c.get("name"));
                    cats.add(row);
                }
            }
            return Map.of("success", true, "categories", cats);
        } catch (Exception e) {
            LOGGER.error("getUserCategories proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Quick-add a category from the Product form → catalog POST /categories {name}. Returns the created {id,name,...}. */
    @PostMapping("/addCategory")
    @ResponseBody
    public Map<String, Object> addCategory(@RequestBody final Map<String, Object> body) {
        try {
            return catalog.postJson("/categories", body);
        } catch (Exception e) {
            LOGGER.error("addCategory proxy error", e);
            return failure(e);
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

    /** Correct a product's on-hand — decrease (a mistaken over-add) or increase — via inventory {@code /stock/adjust}.
     *  Server-side guarded (a DECREASE below zero is rejected: "Insufficient stock") and audited (reason/who/when). */
    @PostMapping("/adjustProductStock")
    @ResponseBody
    public Map<String, Object> adjustProductStock(@RequestBody final Map<String, Object> body) {
        try {
            Map<String, Object> dto = new java.util.HashMap<>();
            dto.put("productId", body.get("productId"));
            dto.put("adjustmentType", body.getOrDefault("adjustmentType", "DECREASE"));   // INCREASE | DECREASE
            dto.put("quantity", body.get("quantity"));
            dto.put("reason", body.getOrDefault("reason", "Manual stock correction"));
            Map<String, Object> resp = inventory.postJson("/stock/adjust", dto);
            Map<String, Object> out = new java.util.HashMap<>();
            out.put("success", resp != null && Boolean.TRUE.equals(resp.get("success")));
            if (resp != null && resp.get("message") != null) out.put("message", resp.get("message"));
            return out;
        } catch (Exception e) {
            LOGGER.error("adjustProductStock proxy error", e);
            return failure(e);   // surfaces the inventory error body (e.g. "Insufficient stock")
        }
    }

    /**
     * Full product pre-fill for the back-office screens: on-hand + sell price + FEFO batches + description.
     * Proxies business-service {@code StockController.productStock} (which sources on-hand/batches from inventory
     * and price/description from the catalog master), then merges {@code success:true} so BOTH consumers work off
     * one call: the Product screen's {@code refreshStock} reads {@code {success, stock}}, and the sell/purchase
     * pickers' {@code loadStock} read the full StockDTO ({@code stock, bsellRate, bpurchaseRate, batches, …}).
     * (Previously returned a thin {@code {success, stock}} from the raw inventory level, which is why the pickers'
     * sell-rate/batch pre-fill came up empty.)
     */
    /** Batch on-hand for the whole tenant in ONE call → inventory {@code /stock/levels/detail}: productId →
     *  {onHand, sellable, expired}. The Product screen fills every row's on-hand at once (instead of a per-row
     *  /productStock call) and shows the honest sellable count + an "expired" badge. */
    @GetMapping("/productStockLevels")
    @ResponseBody
    public Map<String, Object> productStockLevels() {
        try {
            Map<String, Object> levels = inventory.get("/stock/levels/detail");
            return Map.of("success", true, "levels", levels != null ? levels : Collections.emptyMap());
        } catch (Exception e) {
            LOGGER.error("productStockLevels proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    @GetMapping("/productStock")
    @ResponseBody
    public Map<String, Object> productStock(final HttpServletRequest request) {
        try {
            Map<String, Object> dto = business.get("/productStock", "productId=" + request.getParameter("productId"));
            Map<String, Object> out = new java.util.HashMap<>();
            if (dto != null) out.putAll(dto);   // stock (on-hand) + bsellRate + bpurchaseRate + batches + iDesc + bexpDate
            out.put("success", true);
            return out;
        } catch (Exception e) {
            LOGGER.error("productStock proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Single-product sellable split for the sell/purchase forms → inventory {@code /stock/sellable/{id}}:
     *  {onHand, sellable, expired}. The sell screen uses it to show "Sellable: N" + an expired badge and to guard
     *  the entered quantity before submit (so the cashier never over-sells into expired/held stock). */
    @GetMapping("/productSellable")
    @ResponseBody
    public Map<String, Object> productSellable(final HttpServletRequest request) {
        try {
            Map<String, Object> d = inventory.get("/stock/sellable/" + request.getParameter("productId"));
            Map<String, Object> out = new java.util.HashMap<>();
            if (d != null) out.putAll(d);   // onHand + sellable + expired
            out.put("success", true);
            return out;
        } catch (Exception e) {
            LOGGER.error("productSellable proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** P11 register (slice 58): the org's quarantined (non-sellable) lots. */
    @GetMapping("/quarantineList")
    @ResponseBody
    public Map<String, Object> quarantineList() {
        try {
            Map<String, Object> resp = inventory.get("/stock/quarantine");
            return resp != null ? resp : Collections.singletonMap("items", java.util.Collections.emptyList());
        } catch (Exception e) {
            LOGGER.error("quarantineList proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** P11 register (slice 58): dispose a quarantined lot. */
    @PostMapping("/disposeQuarantine")
    @ResponseBody
    public Map<String, Object> disposeQuarantine(@RequestBody final Map<String, Object> body) {
        try {
            return inventory.postJson("/stock/quarantine/" + body.get("id") + "/dispose", Collections.emptyMap());
        } catch (Exception e) {
            LOGGER.error("disposeQuarantine proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }
}

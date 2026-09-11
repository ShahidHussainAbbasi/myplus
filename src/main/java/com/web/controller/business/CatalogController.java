package com.web.controller.business;

import com.web.util.ProxyErrors;
import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    /**
     * Kept as a one-line delegation so the ~40 call sites in this class stay unchanged.
     *
     * <p>This method used to hold the logic itself, and was the ONLY place in the monolith that relayed a
     * downstream reason or let the trial/demo upsell through. That is now {@link ProxyErrors}, shared by
     * every proxy controller — this class had the right behaviour and 20 others did not.
     */
    private Map<String, Object> failure(Exception e) {
        return ProxyErrors.failure(e);
    }

    /** Catalog products for the picker. Pass-through of paging params, e.g. /catalogProducts?size=1000. */
    @GetMapping("/catalogProducts")
    @ResponseBody
    public Map<String, Object> products(final HttpServletRequest request) {
        try {
            return catalog.get("/products", request.getQueryString());
        } catch (Exception e) {
            LOGGER.error("catalogProducts proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /**
     * PERF-8 — the lean read behind every product picker.
     *
     * <p>Distinct from {@code /catalogProducts} above, which serves the product LIST and returns all 23
     * fields including deactivated rows. This returns a handful of fields for active products only — id,
     * name, price, and (SER-6) {@code requiresSerial}, which decides whether the sale screen shows the
     * serial box. Both exist because a list and a picker want genuinely different things; serving one from
     * the other was transferring 83% of each product to be discarded by the browser.
     *
     * <p>A {@code Map} pass-through on purpose: a typed twin here would have to gain every field the
     * projection gains, and the one that forgot would drop it silently on the way to the browser.
     */
    @GetMapping("/catalogProductPicker")
    @ResponseBody
    public Map<String, Object> productPicker(final HttpServletRequest request) {
        try {
            return catalog.get("/products/picker", request.getQueryString());
        } catch (Exception e) {
            LOGGER.error("catalogProductPicker proxy error", e);
            return ProxyErrors.failure(e);
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
            // NEWEST FIRST — `size=1000` alone silently truncated the catalogue.
            //
            // The page cap is fine on its own, but with the default (ascending id) ordering it returns the
            // OLDEST thousand, so once a tenant passes 1000 products their most recent ones stop appearing
            // on the Product screen entirely — no message, no indicator, they are simply not in the list.
            // Measured on the demo org: 1042 products, `/getUserProduct` returned 983 with a max id of 1594
            // while the newest was 1636. A shopkeeper would add a product and not find it.
            //
            // `sort=id,desc` makes the window follow the tenant forward. It does NOT change what the user
            // sees ordered by — DataTables sorts client-side — only WHICH thousand rows are fetched, which
            // is the half that was wrong. A proper fix is server-side paging/search on this screen; this
            // keeps the same one request while making the truncation land on the rows least likely to be
            // wanted rather than the most.
            Map<String, Object> resp = catalog.get("/products", "size=1000&sort=id,desc");
            java.util.List<Map<String, Object>> collection = new java.util.ArrayList<>();
            Object data = (resp != null) ? resp.get("data") : null;
            if (data instanceof Map<?, ?> page && page.get("content") instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> p)) continue;
                    boolean inactive = Boolean.FALSE.equals(p.get("isActive"));
                    if (inactive && !includeInactive) continue;   // deactivated → hidden unless "Show inactive"
                    collection.add(productRow(p));
                }
            }
            Map<String, Object> out = new java.util.HashMap<>();
            out.put("status", collection.isEmpty() ? "NOT_FOUND" : "SUCCESS");
            out.put("collection", collection);
            return out;
        } catch (Exception e) {
            LOGGER.error("getUserProduct proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /**
     * ONE catalog product, projected into the row shape every product screen reads.
     *
     * <h3>⚠ THIS PROJECTION IS AN ALLOW-LIST</h3>
     * Every field is copied by hand, so anything not named here is <b>SILENTLY DROPPED</b> on the way to the
     * browser. The pack columns existed, the entity carried them, the DTO exposed them and catalog returned
     * them — and the product form still saw nothing, because the loop never mentioned them. Same shape as the
     * gl_outbox defect: a new field needs every copy point or it vanishes, and nothing errors when one is
     * missed. <b>If a later slice adds a product field, it must be added HERE.</b>
     *
     * <p>It is a method rather than an inline loop for exactly that reason. {@code /getUserProduct} and
     * {@code /getProductPage} return the same rows to the same grid; as two copies, the next field added
     * would reach whichever screen the author happened to be looking at and vanish from the other — an
     * allow-list drop is hard enough to spot once without a second one to keep in step with it.
     */
    private Map<String, Object> productRow(Map<?, ?> p) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", p.get("id"));
        row.put("name", p.get("name"));
        row.put("sku", p.get("sku"));
        // Carried so the form's "already registered" panel can match a scanned/typed barcode too — without it
        // that panel would silently never match on barcode and read as "no duplicate".
        row.put("barcode", p.get("barcode"));
        row.put("unit", p.get("unit"));
        row.put("sellingPrice", p.get("sellingPrice"));
        // Last rates stamped by the purchase flow (Option B) — the Product list's "last bought / last sold at"
        // columns come straight off this row, with no second call.
        row.put("lastPurchaseRate", p.get("lastPurchaseRate"));
        row.put("lastSaleRate", p.get("lastSaleRate"));
        row.put("lastRateAt", p.get("lastRateAt"));
        row.put("packSize", p.get("packSize"));           // U1 — the pack rules
        row.put("looseUnit", p.get("looseUnit"));
        row.put("looseUnitPlural", p.get("looseUnitPlural"));
        row.put("allowLoose", p.get("allowLoose"));
        row.put("defaultSellUnit", p.get("defaultSellUnit"));
        row.put("taxRate", p.get("taxRate"));
        row.put("categoryName", p.get("categoryName"));
        row.put("manufacturer", p.get("manufacturer"));
        row.put("description", p.get("description"));
        row.put("isActive", p.get("isActive") == null ? Boolean.TRUE : p.get("isActive"));   // Status / Reactivate
        row.put("userId", p.get("createdBy"));   // keeps loadDataTable's userId bookkeeping happy
        return row;
    }

    /**
     * The Product grid's PAGED read — 50 rows at a time, filtered and searched on the SERVER.
     *
     * <h3>Why this is a new endpoint and not a change to {@code /getUserProduct}</h3>
     * {@code /getUserProduct} has <b>7 consumers, and 6 of them need the WHOLE catalogue</b>: the duplicate-SKU
     * index on the product form, labels.js, stock-count.js, report-filters.js, and the two picker fills in
     * business.js. Paging it would not have slowed those screens down — it would have made them <i>wrong</i>,
     * silently: a duplicate check that only sees page 1 reports no duplicate. Only the grid wants a page, so
     * only the grid gets a new endpoint.
     *
     * <h3>Search moves to the server WITH the paging, in the same slice</h3>
     * These cannot ship apart. DataTables searches the rows it holds; at 50 rows a page its box would search
     * 50 products out of 1,042 and confidently report "No matching records" for a product the tenant owns.
     * That is a worse screen than the one being replaced, so {@code q} is handled by catalog — where it also
     * matches manufacturer and category, which is what the client-side box covered.
     *
     * <p>Page metadata is returned as a SIBLING of {@code collection}, since this envelope is a plain Map and
     * has room for it.
     */
    @GetMapping("/getProductPage")
    @ResponseBody
    public Map<String, Object> getProductPage(final HttpServletRequest request) {
        try {
            boolean includeInactive = "true".equalsIgnoreCase(request.getParameter("includeInactive"));
            int page = parseInt(request.getParameter("page"), 0, 0, Integer.MAX_VALUE);
            /*
             * Bounded, not trusted: `size=100000` would be the unbounded read this endpoint exists to end,
             * wearing a query parameter.
             *
             * The ceiling is 1000 rather than a page-sized number because the grid's "All" — which the
             * export path uses, so a 50-row page never becomes a 50-row spreadsheet — has to reach it.
             * 1000 is deliberately the SAME ceiling {@code /getUserProduct} already used every time this
             * screen opened: picking anything higher would introduce a larger read than the one being
             * removed.
             */
            int size = parseInt(request.getParameter("size"), 50, 1, 1000);

            StringBuilder qs = new StringBuilder();
            qs.append("page=").append(page).append("&size=").append(size)
              .append("&sort=").append(enc(sortOrDefault(request.getParameter("sort"))));
            if (includeInactive) qs.append("&includeInactive=true");
            String q = request.getParameter("q");
            if (q != null && !q.isBlank()) qs.append("&q=").append(enc(q.trim()));
            // "uncategorised" is a DISTINCT request from omitting the category: null already means "any".
            if ("true".equalsIgnoreCase(request.getParameter("uncategorised"))) {
                qs.append("&uncategorised=true");
            } else {
                String category = request.getParameter("category");
                if (category != null && !category.isBlank()) qs.append("&category=").append(enc(category.trim()));
            }

            Map<String, Object> resp = catalog.get("/products/search", qs.toString());
            java.util.List<Map<String, Object>> collection = new java.util.ArrayList<>();
            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            Object data = (resp != null) ? resp.get("data") : null;
            if (data instanceof Map<?, ?> pg) {
                if (pg.get("content") instanceof java.util.List<?> list) {
                    for (Object o : list) if (o instanceof Map<?, ?> p) collection.add(productRow(p));
                }
                /*
                 * Straight off the page catalog returned — never recomputed here. A total the browser derived
                 * from the rows it can see is the classic "Showing 1-50 of 50" bug.
                 *
                 * ⚠ The KEYS ON THE LEFT are this endpoint's contract; the ones on the right are
                 * {@code common.web.PageResponse}'s fields, and they are NOT the same words. Reading
                 * {@code "page"} and {@code "size"} — the names Spring Data's own Page uses — returns null
                 * from every one of them, and null is not an error: the grid still draws, the totals just
                 * quietly stop being there. Renamed here rather than at the caller so the browser sees one
                 * vocabulary across both paged endpoints.
                 */
                meta.put("page", pg.get("pageNo"));
                meta.put("size", pg.get("pageSize"));
                meta.put("totalElements", pg.get("totalElements"));
                meta.put("totalPages", pg.get("totalPages"));
                meta.put("last", pg.get("last"));
                // PageResponse carries no "first" flag; page 0 is the only thing it could mean.
                meta.put("first", Integer.valueOf(0).equals(pg.get("pageNo")));
            }
            Map<String, Object> out = new java.util.HashMap<>();
            // A LATER page that is legitimately empty is still SUCCESS — "NOT_FOUND" here would make the grid
            // show its no-data message for a search that simply ran past the end.
            out.put("status", (!collection.isEmpty() || page > 0) ? "SUCCESS" : "NOT_FOUND");
            out.put("collection", collection);
            out.put("page", meta);
            return out;
        } catch (Exception e) {
            LOGGER.error("getProductPage proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /**
     * Products per category — the dashboard's category card.
     *
     * <p>Counted by catalog in ONE grouped query rather than by paging the catalogue and tallying here: a
     * tenant with 1,042 products would otherwise pay a full download to render a summary.
     */
    @GetMapping("/getCategoryCounts")
    @ResponseBody
    public Map<String, Object> getCategoryCounts() {
        try {
            Map<String, Object> resp = catalog.get("/products/category-counts", null);
            java.util.List<Map<String, Object>> collection = new java.util.ArrayList<>();
            if (resp != null && resp.get("data") instanceof java.util.List<?> list) {
                for (Object o : list) if (o instanceof Map<?, ?> m) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("categoryId", m.get("categoryId"));
                    row.put("categoryName", m.get("categoryName"));
                    row.put("uncategorised", m.get("uncategorised"));
                    row.put("count", m.get("count"));
                    collection.add(row);
                }
            }
            Map<String, Object> out = new java.util.HashMap<>();
            out.put("status", collection.isEmpty() ? "NOT_FOUND" : "SUCCESS");
            out.put("collection", collection);
            return out;
        } catch (Exception e) {
            LOGGER.error("getCategoryCounts proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /**
     * Product properties the grid may sort by, and the ONLY ones.
     *
     * <h3>Why an allow-list and not a pass-through</h3>
     * This string lands in Spring Data's {@code Pageable} as a property path. An unknown one raises
     * {@code PropertyReferenceException} — a 500 on a grid header click — and a <i>known but unintended</i>
     * one lets a caller order by, and so probe the ordering of, whatever it can name through the entity
     * graph. Neither is a risk worth carrying to save a list of eleven strings.
     *
     * <p>{@code onHand} is absent on purpose: stock lives in inventory-service, and catalog cannot order by
     * a column it does not have. The grid marks that header unorderable for the same reason.
     */
    private static final java.util.Set<String> SORTABLE = java.util.Set.of(
            "id", "name", "sku", "unit", "sellingPrice", "lastPurchaseRate", "lastSaleRate",
            "taxRate", "category.name", "manufacturer", "isActive");

    /** {@code "<field>,<dir>"} if the field is sortable, else newest-first. */
    private static String sortOrDefault(String raw) {
        final String fallback = "id,desc";
        if (raw == null || raw.isBlank()) return fallback;
        String[] parts = raw.split(",", 2);
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) return fallback;
        boolean asc = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim());
        return field + "," + (asc ? "asc" : "desc");
    }

    /** Bounded integer parse — a bad value falls back to the default rather than 500ing the grid. */
    private static int parseInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
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

    /**
     * C6: set the per-product TRACKING policy — serial/IMEI and batch → catalog PUT
     * {@code /products/{id}/tracking-flags}.
     *
     * <p>Its own endpoint rather than fields on {@code /updateProduct}, mirroring how the clinical flags are
     * handled: these are POLICY, not product data. They are ADMIN-gated and capability-gated downstream, and
     * folding them into the general update would mean every ordinary product edit carried a payload that can
     * be refused for a reason unrelated to what the operator changed.
     *
     * <p>The refusal is NOT produced here. catalog-service decides, because it is the single writer and the
     * only place that can enforce "tenant capability AND product policy" without a race. This proxy just
     * carries the answer back — including the 400 an unentitled tenant gets, whose message is written for a
     * shopkeeper.
     */
    @PostMapping("/setProductTracking")
    @ResponseBody
    public Map<String, Object> setProductTracking(final HttpServletRequest request) {
        try {
            String id = request.getParameter("id");
            StringBuilder qs = new StringBuilder();
            appendFlag(qs, "requiresSerial", request.getParameter("requiresSerial"));
            appendFlag(qs, "tracksBatch", request.getParameter("tracksBatch"));
            // Flags are @RequestParam upstream, and an OMITTED one means "leave this policy alone" — so a
            // blank must not be sent as an empty string, which would bind as null and read the same but
            // relies on Spring's coercion rather than saying it.
            return catalog.putJson("/products/" + id + "/tracking-flags"
                    + (qs.length() > 0 ? "?" + qs : ""), java.util.Map.of());
        } catch (Exception e) {
            LOGGER.error("setProductTracking proxy error", e);
            return failure(e);
        }
    }

    private static void appendFlag(StringBuilder qs, String name, String value) {
        if (value == null || value.isBlank()) return;
        if (qs.length() > 0) qs.append('&');
        qs.append(name).append('=').append("true".equalsIgnoreCase(value));
    }

    /** Barcode-first sell: resolve a scanned code (barcode or sku) to a ProductRef → catalog /products/lookup.
     *  A miss (404) or downstream hiccup returns {} so the sell screen shows "not found" without a scary error. */
    /**
     * U7 — resolve a scanned code to this many of this product, in this unit.
     *
     * <p>A raw pass-through of catalog's {@code /products/scan}, deliberately: the body is a
     * {@code ScanResolution} whose shape the till reads directly, and a field-by-field projection here would
     * be the fourth instance of the allow-list defect this codebase has already paid for three times
     * (gl_outbox, the product row projection, the monolith SellDTO).
     *
     * <p>An empty object on failure, exactly like {@code lookupProduct}: a mis-scan is normal and must not
     * log an error or stop the till.
     */
    @GetMapping(value = "/scanProduct", produces = "application/json")
    @ResponseBody
    public String scanProduct(final HttpServletRequest request) {
        String code = request.getParameter("code");
        if (code == null || code.isBlank()) return "{}";
        try {
            return catalog.getString("/products/scan?code="
                    + java.net.URLEncoder.encode(code.trim(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A refusal (e.g. a sticker for a product no longer sold loose) arrives here as a 4xx. Relay the
            // service's sentence rather than an empty object, or the cashier is told nothing at all.
            String message = com.web.util.ProxyErrors.statusError(e).get("message") instanceof String m ? m : null;
            return message == null ? "{}" : "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }

    /** U12 — every sticker in the shop, for the label sheet. One request, not one per product. */
    @GetMapping(value = "/allProductBarcodes", produces = "application/json")
    @ResponseBody
    public Map<String, Object> allProductBarcodes() {
        try {
            return catalog.get("/products/barcodes");
        } catch (Exception e) {
            LOGGER.error("allProductBarcodes proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** U7 — the shop's own stickers on one product. */
    @GetMapping(value = "/productBarcodes", produces = "application/json")
    @ResponseBody
    public Map<String, Object> productBarcodes(final HttpServletRequest request) {
        try {
            return catalog.get("/products/" + request.getParameter("productId") + "/barcodes");
        } catch (Exception e) {
            LOGGER.error("productBarcodes proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** U7 — register a sticker. The refusals that keep it from shadowing a real barcode live in catalog. */
    @PostMapping("/addProductBarcode")
    @ResponseBody
    public Map<String, Object> addProductBarcode(@RequestBody final Map<String, Object> body) {
        try {
            Object pid = body.get("productId");
            Map<String, Object> resp = catalog.postJson("/products/" + pid + "/barcodes", body);
            return Map.of("success", true, "data", resp == null ? Map.of() : resp);
        } catch (Exception e) {
            LOGGER.error("addProductBarcode proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** U7 — remove a sticker. */
    @PostMapping("/removeProductBarcode")
    @ResponseBody
    public Map<String, Object> removeProductBarcode(@RequestBody final Map<String, Object> body) {
        try {
            catalog.delete("/products/barcodes/" + body.get("id"));
            return Map.of("success", true);
        } catch (Exception e) {
            LOGGER.error("removeProductBarcode proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

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

    /**
     * Server-side duplicate-NAME check for the Product form, fired when the Name field loses focus →
     * catalog {@code /products/name-check}. Flattened to {@code {success, exists, id, name, sku, active}} so the
     * form reads one shape (same reshaping habit as {@code /getUserProduct}).
     *
     * <p>A failed downstream call returns {@code success:false} WITHOUT {@code exists}, deliberately: reporting
     * {@code exists:false} when nothing was actually checked would tell the user the name is free on the
     * strength of a call that never happened.
     */
    @GetMapping("/productNameCheck")
    @ResponseBody
    public Map<String, Object> productNameCheck(final HttpServletRequest request) {
        String name = request.getParameter("name");
        if (name == null || name.isBlank()) return Map.of("success", true, "exists", false);
        try {
            StringBuilder qs = new StringBuilder("name=").append(
                    java.net.URLEncoder.encode(name.trim(), java.nio.charset.StandardCharsets.UTF_8));
            String excludeId = request.getParameter("excludeId");
            if (excludeId != null && !excludeId.isBlank()) {
                qs.append("&excludeId=").append(
                        java.net.URLEncoder.encode(excludeId.trim(), java.nio.charset.StandardCharsets.UTF_8));
            }
            Map<String, Object> resp = catalog.get("/products/name-check", qs.toString());
            Map<String, Object> out = new java.util.HashMap<>();
            out.put("success", true);
            out.put("exists", false);
            if (resp != null && resp.get("data") instanceof Map<?, ?> d) {
                out.put("exists", Boolean.TRUE.equals(d.get("exists")));
                out.put("id", d.get("id"));
                out.put("name", d.get("name"));
                out.put("sku", d.get("sku"));
                out.put("active", d.get("active"));
            }
            return out;
        } catch (Exception e) {
            LOGGER.error("productNameCheck proxy error", e);
            return ProxyErrors.failure(e);
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

    // ── B2B-P2 (#10): contract & tier price rules ────────────────────────────────────────────────────

    /** The tenant's price rules → catalog GET /price-rules (raw JSON array for the Price Rules screen). */
    /*
     * ── Bonus / free-goods schemes (task #17 P1) ──────────────────────────────────────────────────────
     *
     * Straight proxies. Tenant scoping, the ADMIN_PRIVILEGE bar on authoring, and the mandatory-field rules
     * all live in catalog-service, which is the only side that can see the caller's org — the monolith must
     * not re-implement any of them, or the two will disagree about what a valid scheme is.
     */
    @GetMapping("/bonusSchemes")
    @ResponseBody
    public Map<String, Object> bonusSchemes(final HttpServletRequest request) {
        try {
            String q = request.getQueryString();
            return catalog.get("/bonus-schemes", q == null ? "" : q);
        } catch (Exception e) {
            LOGGER.error("bonusSchemes proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @PostMapping("/bonusScheme")
    @ResponseBody
    public Map<String, Object> createBonusScheme(@RequestBody Map<String, Object> body) {
        try {
            return catalog.postJson("/bonus-schemes", body);
        } catch (Exception e) {
            LOGGER.error("createBonusScheme proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @PutMapping("/bonusScheme/{id}")
    @ResponseBody
    public Map<String, Object> updateBonusScheme(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            return catalog.putJson("/bonus-schemes/" + id, body);
        } catch (Exception e) {
            LOGGER.error("updateBonusScheme proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** The entitlement calculator — one definition of "what does this paid quantity earn". */
    @PostMapping("/bonusScheme/preview")
    @ResponseBody
    public Map<String, Object> previewBonusScheme(@RequestBody Map<String, Object> body) {
        try {
            return catalog.postJson("/bonus-schemes/preview", body);
        } catch (Exception e) {
            LOGGER.error("previewBonusScheme proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @GetMapping("/priceRules")
    @ResponseBody
    public String priceRules() {
        try { return catalog.getString("/price-rules"); }
        catch (Exception e) { LOGGER.error("priceRules proxy error", e); return "[]"; }
    }

    /**
     * "What does this buyer pay for these lines?" → catalog POST /price-rules/quote.
     *
     * <p>B2B-P2-UI: the sell screen needs this because the CASHIER'S submitted rate wins server-side (a
     * deliberate override must beat a rule), and the screen was pre-filling that rate from the CATALOG price —
     * so a contract price was resolved, recorded as the line's reason, and then not charged. The till asks
     * what the buyer pays and puts THAT in the rate box, where the cashier can still override it.
     *
     * <p>Open to any authenticated user, matching the catalog endpoint: every till needs it on every sale, it
     * answers only for the caller's own tenant, and it returns prices the cashier is about to charge anyway.
     * The client sends ids and quantities only — it never sends a price and is never believed about one.
     */
    @PostMapping("/priceQuote")
    @ResponseBody
    public Map<String, Object> priceQuote(@RequestBody final Map<String, Object> body) {
        try { return catalog.postJson("/price-rules/quote", body); }
        catch (Exception e) {
            // A pricing outage must never stop a sale: an empty quote means "charge catalog", which is
            // exactly today's behaviour. Same fallback SagaSellService takes for the same reason.
            LOGGER.warn("priceQuote proxy error — this sale prices at catalog rates ({})", e.toString());
            return Collections.singletonMap("lines", Collections.emptyList());
        }
    }

    /** Create (no id) or update (with id) a price rule → catalog POST/PUT /price-rules. */
    @PostMapping("/savePriceRule")
    @ResponseBody
    public Map<String, Object> savePriceRule(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            return (id != null && !id.toString().isBlank())
                    ? catalog.putJson("/price-rules/" + id.toString().trim(), body)
                    : catalog.postJson("/price-rules", body);
        } catch (Exception e) { LOGGER.error("savePriceRule proxy error", e); return failure(e); }
    }

    /** Delete a price rule → catalog DELETE /price-rules/{id}. */
    @PostMapping("/deletePriceRule")
    @ResponseBody
    public Map<String, Object> deletePriceRule(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            if (id == null || id.toString().isBlank()) return Collections.singletonMap("success", false);
            catalog.delete("/price-rules/" + id.toString().trim());
            return Collections.singletonMap("success", true);
        } catch (Exception e) { LOGGER.error("deletePriceRule proxy error", e); return failure(e); }
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
            return ProxyErrors.failure(e);
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
            return ProxyErrors.failure(e);
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
            /*
             * ⚠ THE COST, which this map used to drop on the floor.
             *
             * StockImportLine has carried purchasePrice, costPrice and paidTotal all along and
             * StockImportService persists them — but this method builds the line FIELD BY FIELD, so a
             * caller could send a cost and watch it vanish with a 200 and "success": true.
             *
             * What that costs, end to end: stock imported here has purchase_price NULL, so
             * ReservationService.unitCostOf() returns null, so the FEFO pick carries no cost, so
             * sell_batch.unit_cost is NULL — and the sale's own record of what it consumed says the goods
             * were free. The GL still posts COGS (it computes costPrice x quantity from the product), so
             * the books and the traceability DISAGREE, silently, on every sale that draws down imported
             * stock. Measured on this database: 2,930 of 4,006 stock entries carry no cost at all, and
             * 1,437 of 1,682 sell_batch rows have a NULL unit cost.
             *
             * This is the FOURTH instance of the same defect in this codebase — gl_outbox dropping event
             * fields, the product row projection, the monolith SellDTO, and now this. A hand-built map
             * over a DTO that already has the field is the shape to distrust.
             */
            if (body.get("purchasePrice") != null) line.put("purchasePrice", body.get("purchasePrice"));
            if (body.get("costPrice") != null)     line.put("costPrice", body.get("costPrice"));
            if (body.get("paidTotal") != null)     line.put("paidTotal", body.get("paidTotal"));
            /*
             * ⭐ PERF-9 — answer with the new ON-HAND, so the screen needs no second call.
             *
             * This returned a bare row count, so catalog-products.js had to follow every add with
             * GET /productStock to learn the figure it was about to display: two browser round trips to
             * change one number. Inventory computes that number while writing and now returns it
             * (StockImportResult) — the same thing reconcilePurchase has always done for a purchase edit.
             *
             * `stock` is ABSENT rather than 0 when inventory did not report one: a missing figure and a
             * genuine zero must not look alike, and the client falls back to its own read in that case.
             */
            Map<String, Object> res = inventory.postJson("/stock/import", Collections.singletonList(line));
            Map<String, Object> out = new java.util.HashMap<>();
            out.put("success", true);
            if (res != null) {
                out.put("created", res.get("created"));
                Object onHand = res.get("onHand");
                if (onHand instanceof Map<?, ?> byProduct && productId != null) {
                    /*
                     * ⚠ MATCH ON THE NUMERIC VALUE, not on toString().
                     *
                     * JSON object keys are always strings ("5422"), while productId arrives from the request
                     * body as whatever Jackson chose — Integer here, but a Long or a Double would render as
                     * "5422" or "5422.0" and silently miss. A miss is not visible: `stock` would simply be
                     * absent and the client would fall back to its extra round trip, which is the exact cost
                     * this change exists to remove. Comparing numerically cannot drift.
                     */
                    /*
                     * ⚠ AND IT CANNOT THROW. The stock is ALREADY WRITTEN by the time we get here, so an
                     * exception escaping this block would reach the catch below and report a FAILURE for an
                     * add that succeeded — the worst possible outcome, and one the operator would answer by
                     * adding the stock a second time. A figure we cannot read is simply absent, and the
                     * client falls back to its own read.
                     */
                    try {
                        long want = Double.valueOf(productId.toString()).longValue();
                        for (Map.Entry<?, ?> e : byProduct.entrySet()) {
                            if (e.getKey() == null || e.getValue() == null) continue;
                            if (Double.valueOf(e.getKey().toString()).longValue() == want) {
                                out.put("stock", e.getValue());
                                break;
                            }
                        }
                    } catch (RuntimeException unreadable) {
                        LOGGER.debug("addProductStock: could not match the on-hand key; the screen will re-read", unreadable);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            LOGGER.error("addProductStock proxy error", e);
            return ProxyErrors.failure(e);
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
            // PERF-9, same as addProductStock above: the adjustment carries the resulting on-hand, so the
            // screen updates from the write instead of reading it back.
            if (resp != null && resp.get("data") instanceof Map) {
                Object onHand = ((Map<?, ?>) resp.get("data")).get("resultingOnHand");
                if (onHand != null) out.put("stock", onHand);
            }
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
            return ProxyErrors.failure(e);
        }
    }

    /**
     * OMS O5a — release this tenant's expired stock holds now.
     *
     * <p>An unconfirmed hold subtracts from sellable stock, so a sale that reserved and then failed to confirm
     * or compensate leaves stock that is physically present and cannot be sold. A scheduled sweep returns it
     * within minutes; this is the "I have just fixed the outage, give me my stock back now" button.
     *
     * <p>Owner/admin — the {@code @PreAuthorize} on inventory-service is the real gate.
     */
    @org.springframework.web.bind.annotation.PostMapping("/sweepStockHolds")
    @ResponseBody
    public Map<String, Object> sweepStockHolds() {
        try {
            return inventory.postJson("/reservations/sweep", Collections.emptyMap());
        } catch (Exception e) {
            LOGGER.error("sweepStockHolds proxy error", e);
            return ProxyErrors.failure(e);
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
            return ProxyErrors.failure(e);
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
            return ProxyErrors.failure(e);
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
            return ProxyErrors.failure(e);
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
            return ProxyErrors.failure(e);
        }
    }
}

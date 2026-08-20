package com.web.controller.business;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;
import com.web.util.CatalogRestClient;

/**
 * Slices I1 + I2 — browser-facing proxies for the CSV template/import endpoints.
 *
 * <h3>Two services own the importable masters, so this routes per ENTITY</h3>
 * business-service owns Customer, catalog-service owns Product. Each runs its own
 * {@code ImportController} over its own registry, because a service can only import what it stores. This
 * class is the only place that knows which is which.
 *
 * <p>Shipped WITH the slice rather than after it. An endpoint with no proxy is unreachable from the only UI
 * this platform has — review finding R7, hit three times in the OMS programme.
 *
 * <h3>It normalises TWO response envelopes into one</h3>
 * business-service answers in its monolith-facing {@code GenericResponse}
 * ({@code {status, message, object, collection}}); catalog-service answers in the platform
 * {@code ApiResponse} ({@code {success, message, data, statusCode}}). Left alone, the browser would need a
 * branch per service, and adding a third importable entity would mean touching the front end again.
 *
 * <p>So catalog's shape is translated here into the one {@code data-import.js} already speaks. The browser
 * keeps a single contract and never learns which service stores what — which is the whole point of the
 * monolith being the only thing that does.
 *
 * <p>No authorisation decision is taken here. Both services gate on {@code ADMIN_PRIVILEGE}, so a non-admin
 * reaching these methods still gets the refusal from the service that owns the data.
 */
@Controller
public class ImportProxyController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired private BusinessRestClient business;
    @Autowired private CatalogRestClient catalog;

    /** Entities served by catalog-service. Everything else falls through to business-service. */
    private static final List<String> CATALOG_ENTITIES = List.of("product");

    private boolean isCatalog(String entity) {
        return entity != null && CATALOG_ENTITIES.contains(entity.trim().toLowerCase());
    }

    // ── entities: merge BOTH registries ─────────────────────────────────────────────────────────────────────

    /**
     * The union of what every service can import — this is what the grid draws its buttons from.
     *
     * <p>Each half is fetched independently and a failure in one does not suppress the other: if
     * catalog-service is down the Customer buttons still appear, and vice versa. That is the same "no spec,
     * no button" rule I1 established, applied per service rather than per platform.
     */
    @GetMapping("/import/entities")
    @ResponseBody
    public Map<String, Object> entities() {
        List<Object> merged = new ArrayList<>();

        try {
            merged.addAll(listOf(business.get("/import/entities")));
        } catch (Exception e) {
            LOGGER.error("import entities: business-service unavailable", e);
        }
        try {
            merged.addAll(listOf(catalog.get("/import/entities")));
        } catch (Exception e) {
            LOGGER.error("import entities: catalog-service unavailable", e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "SUCCESS");
        out.put("collection", merged);
        return out;
    }

    /** Pull the list out of either envelope: GenericResponse puts it in `collection`, ApiResponse in `data`. */
    @SuppressWarnings("unchecked")
    private List<Object> listOf(Map<String, Object> body) {
        if (body == null) return Collections.emptyList();
        for (String key : new String[] { "collection", "data", "object" }) {
            Object v = body.get(key);
            if (v instanceof List) return (List<Object>) v;
        }
        return Collections.emptyList();
    }

    // ── template ────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/import/{entity}/template.csv")
    @ResponseBody
    public ResponseEntity<String> template(@PathVariable("entity") String entity) {
        try {
            String path = "/import/" + entity + "/template.csv";
            String csv = isCatalog(entity) ? catalog.getString(path) : business.getString(path);
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + entity + "-import-template.csv\"")
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .body(csv);
        } catch (Exception e) {
            LOGGER.error("import template proxy error for {}", entity, e);
            return ResponseEntity.status(502).body("Could not build the template. Please try again.");
        }
    }

    // ── validate / commit ───────────────────────────────────────────────────────────────────────────────────

    /** Dry run — writes nothing, returns the row-by-row report. */
    @PostMapping("/import/{entity}/validate")
    @ResponseBody
    public Map<String, Object> validate(@PathVariable("entity") String entity,
                                        @RequestBody Map<String, Object> body) {
        return relay(entity, "/import/" + entity + "/validate", body);
    }

    /** Commit — writes only if every row is acceptable. */
    @PostMapping("/import/{entity}/commit")
    @ResponseBody
    public Map<String, Object> commit(@PathVariable("entity") String entity,
                                      @RequestBody Map<String, Object> body) {
        return relay(entity, "/import/" + entity + "/commit", body);
    }

    private Map<String, Object> relay(String entity, String path, Map<String, Object> body) {
        try {
            return isCatalog(entity)
                    ? normalise(catalog.postJson(path, body))
                    : business.postJson(path, body);
        } catch (Exception e) {
            LOGGER.error("import proxy error for {}", path, e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /**
     * Translate catalog's {@code ApiResponse} into the {@code GenericResponse} shape the browser speaks.
     *
     * <p>Only the three fields {@code data-import.js} actually reads are mapped — {@code status},
     * {@code message} and {@code object} — because inventing a fuller translation would imply a fidelity this
     * does not have. A body that is already in GenericResponse shape is passed through untouched, so this is
     * safe to apply to either service if the routing ever changes.
     */
    private Map<String, Object> normalise(Map<String, Object> body) {
        if (body == null) return Collections.singletonMap("status", "ERROR");
        if (body.containsKey("status")) return body;            // already GenericResponse-shaped

        Map<String, Object> out = new LinkedHashMap<>();
        Object success = body.get("success");
        out.put("status", Boolean.TRUE.equals(success) ? "SUCCESS" : "FAILED");
        if (body.get("message") != null) out.put("message", body.get("message"));
        // The report travels in `data` on the way in and `object` on the way out — the browser reads the
        // latter, and a refused commit carries its per-row reasons here just as a successful one does.
        if (body.get("data") != null) out.put("object", body.get("data"));
        return out;
    }

    // ── the report download ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The report as a downloadable CSV.
     *
     * <p>Takes the CSV as a FORM field, not a JSON body: the response is a download, so the browser has to
     * submit a real form for the filename to survive — an XHR would hand the text back to JavaScript with
     * nowhere to put it. The proxy translates form → JSON, so both services keep one body shape.
     */
    @PostMapping(value = "/import/{entity}/report.csv", consumes = "application/x-www-form-urlencoded")
    @ResponseBody
    public ResponseEntity<String> report(@PathVariable("entity") String entity,
                                         @RequestParam("csv") String csvText) {
        try {
            String path = "/import/" + entity + "/report.csv";
            Map<String, String> payload = Collections.singletonMap("csv", csvText);
            String csv = isCatalog(entity)
                    ? catalog.postJsonString(path, payload)
                    : business.postJsonString(path, payload);
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + entity + "-import-report.csv\"")
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .body(csv);
        } catch (Exception e) {
            LOGGER.error("import report proxy error for {}", entity, e);
            return ResponseEntity.status(502).body("Could not build the report.");
        }
    }
}

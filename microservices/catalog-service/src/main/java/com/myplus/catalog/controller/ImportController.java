package com.myplus.catalog.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.imports.CsvWriter;
import com.myplus.common.imports.ImportEngine;
import com.myplus.common.imports.ImportReport;
import com.myplus.common.imports.ImportSpec;
import com.myplus.common.imports.ImportSpecRegistry;
import com.myplus.common.imports.RowResult;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;

/**
 * Slice I2 — the catalog's half of the CSV import surface: download a template, validate a file, import it.
 *
 * <h3>Why a second controller rather than one shared one</h3>
 * A service can only import what it stores. business-service owns Customer and catalog-service owns Product,
 * so each keeps its own {@link ImportSpecRegistry} populated by its own beans, and each exposes the same four
 * routes over it. The alternative — one controller reaching across the network to write another service's
 * master — is the coupling the decomposition exists to prevent.
 *
 * <p>The two controllers are near-identical by design, and that is not duplication worth removing: the
 * behaviour they share lives in {@code common-import} (the engine, the specs, the CSV codec) and what remains
 * here is HTTP plumbing plus this service's own conventions. Note they genuinely differ — this one answers in
 * {@code ApiResponse}, business-service's in its monolith-facing {@code GenericResponse}.
 *
 * <h3>The {@code /api/catalog} prefix is NOT optional</h3>
 * catalog-service is a <b>full-path service</b>: the gateway route for it carries no {@code StripPrefix}
 * (its own config says so: <i>"No StripPrefix: catalog-service controllers are mapped at the full
 * /api/catalog/... path"</i>), so every controller here declares the whole path. business-service is the
 * opposite — {@code StripPrefix=2} — which is why its {@code ImportController} maps at a bare
 * {@code /import/...}. Copying that mapping across cost this slice its first gate run: the gateway forwarded
 * {@code /api/catalog/import/entities}, nothing matched, and the proxy reported a generic failure.
 *
 * <h3>Authority</h3>
 * Everything except {@code /entities} requires {@code ADMIN_PRIVILEGE}. This also closes a real asymmetry: the
 * endpoint this slice deleted, {@code POST /products/import}, could create unlimited products with no gate at
 * all, while {@code DELETE /products/{id}} beside it required {@code DELETE_PRIVILEGE}.
 */
@RestController
@RequestMapping("/api/catalog/import")
public class ImportController {

    private static final Logger LOG = LoggerFactory.getLogger(ImportController.class);

    /** Hard ceiling on the uploaded text. A row cap alone does not stop a single enormous line. */
    private static final int MAX_CHARS = 2 * 1024 * 1024;

    @Autowired private ImportEngine engine;
    @Autowired private ImportSpecRegistry registry;

    /** The body of a validate/commit call: the file, as text. */
    public static class ImportRequest {
        private String csv;
        public String getCsv() { return csv; }
        public void setCsv(String csv) { this.csv = csv; }
    }

    /** Which entities this service can import. The grid draws its buttons from the merged listing. */
    @GetMapping("/entities")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> entities() {
        return ResponseEntity.ok(ApiResponse.success(registry.listing()));
    }

    /**
     * The blank template, generated from {@code spec.columns()} — the SAME list that validates the upload,
     * so the header cannot drift from the parser.
     */
    @RequestMapping(value = "/{entity}/template.csv", method = RequestMethod.GET,
            produces = "text/csv; charset=UTF-8")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<String> template(@PathVariable("entity") String entity) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + spec.entity() + "-import-template.csv\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(engine.templateCsv(spec));
    }

    /** Validate and classify. Writes NOTHING — the report says what a commit would do. */
    @PostMapping("/{entity}/validate")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<ApiResponse<ImportReport>> validate(@PathVariable("entity") String entity,
                                                              @RequestBody ImportRequest body) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null)
            return ResponseEntity.ok(ApiResponse.error("Nothing can be imported into '" + entity + "'.", 400));

        String tooBig = sizeRefusal(body);
        if (tooBig != null) return ResponseEntity.ok(ApiResponse.error(tooBig, 400));

        try {
            return ResponseEntity.ok(ApiResponse.success(
                    engine.dryRun(spec, body.getCsv(), CurrentUser.organizationId(), CurrentUser.userId())));
        } catch (Exception e) {
            LOG.error("I2: validate failed for {}", entity, e);
            return ResponseEntity.ok(ApiResponse.error(
                    "That file could not be read. Check it is the downloaded template.", 400));
        }
    }

    /**
     * Import for real — and only if every row is acceptable.
     *
     * <p>No idempotency key, and that is not an oversight: the import is create-only and duplicate-checked,
     * so a replayed file finds every SKU already present and creates nothing. The guard is in the semantics
     * rather than bolted on beside them, which also covers the operator who re-uploads an hour later.
     */
    @PostMapping("/{entity}/commit")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<ApiResponse<ImportReport>> commit(@PathVariable("entity") String entity,
                                                            @RequestBody ImportRequest body) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null)
            return ResponseEntity.ok(ApiResponse.error("Nothing can be imported into '" + entity + "'.", 400));

        String tooBig = sizeRefusal(body);
        if (tooBig != null) return ResponseEntity.ok(ApiResponse.error(tooBig, 400));

        try {
            ImportReport report = commitTyped(spec, body.getCsv());
            if (!report.isCommitted())
                // success=false AND the report: the operator needs the per-row reasons, not just a message.
                // ApiResponse offers no error(message, data) factory, so the all-args constructor is used
                // directly rather than inventing a second envelope shape for one endpoint.
                return ResponseEntity.ok(new ApiResponse<>(
                        false, "Nothing was imported — fix the rows below and try again.", report, 400));

            LOG.info("I2: imported {} {} row(s) for org {}",
                    report.getToCreate(), entity, CurrentUser.organizationId());
            return ResponseEntity.ok(ApiResponse.success(report));
        } catch (Exception e) {
            LOG.error("I2: commit failed for {}", entity, e);
            return ResponseEntity.ok(ApiResponse.error(
                    "The import could not be completed. Nothing further was written — check the list and retry.", 500));
        }
    }

    /** Captures the registry's wildcard as a type variable so {@code engine.commit} can bind {@code <T>}. */
    private <T> ImportReport commitTyped(ImportSpec<T> spec, String csv) {
        return engine.commit(spec, csv, CurrentUser.organizationId(), CurrentUser.userId());
    }

    /**
     * The dry-run report as a downloadable CSV — for a file too long to read in a modal.
     *
     * <p>Rendered server-side through {@code CsvWriter} rather than assembled in the browser, so the quoting
     * rules and the formula guard have exactly one definition. The messages echo operator-supplied cell
     * content, so this file needs the same protection every other export gets.
     */
    @RequestMapping(value = "/{entity}/report.csv", method = RequestMethod.POST,
            produces = "text/csv; charset=UTF-8")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<String> report(@PathVariable("entity") String entity,
                                         @RequestBody ImportRequest body) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null) return ResponseEntity.notFound().build();
        if (sizeRefusal(body) != null) return ResponseEntity.badRequest().build();

        ImportReport report = engine.dryRun(spec, body.getCsv(),
                CurrentUser.organizationId(), CurrentUser.userId());

        List<List<?>> rows = new ArrayList<>();
        if (report.getFileError() != null) {
            rows.add(List.of("", "FILE", report.getFileError()));
        } else {
            for (RowResult r : report.getRows())
                rows.add(List.of(String.valueOf(r.getRowNumber()),
                        r.getStatus().name(),
                        r.getMessage() == null ? "" : r.getMessage()));
        }

        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + spec.entity() + "-import-report.csv\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(CsvWriter.write(List.of("Row", "Result", "Detail"), rows));
    }

    /**
     * Server-side size cap. The row cap lives in the spec and is enforced by the reader; this stops a single
     * enormous line reaching the parser at all. A cap the client could raise is not a cap.
     */
    private String sizeRefusal(ImportRequest body) {
        if (body == null || body.getCsv() == null || body.getCsv().trim().isEmpty())
            return "No file was supplied.";
        if (body.getCsv().length() > MAX_CHARS)
            return "That file is too large. Split it and import the parts separately.";
        return null;
    }
}

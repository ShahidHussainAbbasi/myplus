package com.myplus.business_service.controller;

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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.util.CsvWriter;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.imports.ImportEngine;
import com.myplus.common.imports.ImportReport;
import com.myplus.common.imports.ImportSpec;
import com.myplus.common.imports.ImportSpecRegistry;
import com.myplus.common.imports.RowResult;
import com.myplus.common.security.AuthenticatedUser;

/**
 * Slice I1 — download a template, validate a filled file, import it.
 *
 * <h3>The endpoint set is deliberately four, and validate is not optional</h3>
 * There is no "just import it" route. A commit is always preceded by a dry run in the UI, and the commit
 * re-validates anyway, so nothing can be written that was not first shown to the operator.
 *
 * <h3>Authority</h3>
 * Everything except {@code /entities} is {@code ADMIN_PRIVILEGE}. Bulk-creating master data is an owner
 * operation, not a counter one. Worth noting the asymmetry this corrects by example: catalog's
 * {@code /products/import} can create unlimited rows with no gate at all, while deleting ONE product beside
 * it requires {@code DELETE_PRIVILEGE}.
 *
 * <p>{@code /entities} is open to any authenticated user because it returns nothing but the list of
 * importable entity names — the browser needs it to decide whether to draw the buttons, and a non-admin who
 * sees a button still gets a 403 from the server, which is the refusal that counts.
 */
@RestController
public class ImportController {

    private static final Logger LOG = LoggerFactory.getLogger(ImportController.class);

    /** Hard ceiling on the uploaded text. A row cap alone does not stop a single 50 MB line. */
    private static final int MAX_CHARS = 2 * 1024 * 1024;

    @Autowired private ImportEngine engine;
    @Autowired private ImportSpecRegistry registry;
    @Autowired private RequestUtil requestUtil;

    private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getOrganizationId(); }
    private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getUserId(); }

    /** The body of a validate/commit call: the file, as text. */
    public static class ImportRequest {
        private String csv;
        public String getCsv() { return csv; }
        public void setCsv(String csv) { this.csv = csv; }
    }

    // ── what is importable ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Which entities have an {@link ImportSpec}. The grid draws its two buttons only for these, so a screen
     * can never offer an import the server has no spec for.
     */
    @GetMapping("/import/entities")
    @ResponseBody
    public GenericResponse entities() {
        List<Map<String, String>> listing = registry.listing();
        return new GenericResponse("SUCCESS", "OK", listing);
    }

    // ── template ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The blank template, generated from {@code spec.columns()} — the SAME list that validates the upload.
     *
     * <p>That single fact is what makes the round trip reliable. The tempting alternative, exporting the
     * grid's own columns client-side, produces a file that cannot be imported back: a grid shows formatted
     * money, a hidden id column, a checkbox column, an actions column, and whatever the operator has hidden.
     */
    @RequestMapping(value = "/import/{entity}/template.csv", method = RequestMethod.GET,
            produces = "text/csv; charset=UTF-8")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<String> template(@PathVariable("entity") String entity) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null) return ResponseEntity.notFound().build();

        String csv = CsvWriter.write(engine.templateHeaders(spec), engine.templateRows(spec));
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + spec.entity() + "-import-template.csv\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(csv);
    }

    // ── dry run ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Validate and classify. Writes NOTHING — the report says what a commit would do. */
    @PostMapping("/import/{entity}/validate")
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse validate(@PathVariable("entity") String entity,
                                    @RequestBody ImportRequest body) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null) return new GenericResponse("FAILED", "Nothing can be imported into '" + entity + "'.");

        String tooBig = sizeRefusal(body);
        if (tooBig != null) return new GenericResponse("FAILED", tooBig);

        try {
            ImportReport report = engine.dryRun(spec, body.getCsv(), orgId(), userId());
            return new GenericResponse("SUCCESS", "Checked", report);
        } catch (Exception e) {
            LOG.error("I1: validate failed for {}", entity, e);
            return new GenericResponse("ERROR", "That file could not be read. Check it is the downloaded template.");
        }
    }

    // ── commit ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Import for real — and only if every row is acceptable.
     *
     * <p><b>No idempotency key is needed and that is not an oversight.</b> The import is create-only and
     * duplicate-checked, so a replayed file finds every contact already present and creates nothing. The
     * guard is in the semantics rather than bolted on beside them, which is the stronger place for it: a key
     * would protect against a double-click and nothing else, while this also covers the operator who
     * re-uploads the same file an hour later having forgotten they already did.
     */
    @PostMapping("/import/{entity}/commit")
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public GenericResponse commit(@PathVariable("entity") String entity,
                                  @RequestBody ImportRequest body) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null) return new GenericResponse("FAILED", "Nothing can be imported into '" + entity + "'.");

        String tooBig = sizeRefusal(body);
        if (tooBig != null) return new GenericResponse("FAILED", tooBig);

        try {
            ImportReport report = commitTyped(spec, body.getCsv());
            if (!report.isCommitted())
                return new GenericResponse("FAILED", "Nothing was imported — fix the rows below and try again.",
                        report);

            LOG.info("I1: imported {} {} row(s) for org {}", report.getToCreate(), entity, orgId());
            return new GenericResponse("SUCCESS",
                    report.getToCreate() + " row(s) imported.", report);
        } catch (Exception e) {
            LOG.error("I1: commit failed for {}", entity, e);
            return new GenericResponse("ERROR",
                    "The import could not be completed. Nothing further was written — check the list and retry.");
        }
    }

    /**
     * Captures the registry's wildcard as a type variable so {@code engine.commit} can bind {@code <T>}.
     *
     * <p>Only a helper because {@code ImportSpec<?>} cannot be passed to a method declared over
     * {@code ImportSpec<T>} directly — this is capture conversion, not a cast, so no unchecked warning and
     * no way for the wrong entity type to slip through.
     */
    private <T> ImportReport commitTyped(ImportSpec<T> spec, String csv) {
        return engine.commit(spec, csv, orgId(), userId());
    }

    // ── the report, as a file ───────────────────────────────────────────────────────────────────────────────

    /**
     * The dry-run report as a downloadable CSV.
     *
     * <p>At 480 skips nobody reads a modal, but a file the operator can open beside their spreadsheet
     * reconciles properly. Rendered SERVER-side through {@code CsvWriter} rather than assembled in the
     * browser, so the quoting rules and the formula guard have exactly one definition — the messages echo
     * operator-supplied cell content, so this file needs the same protection every other export gets.
     */
    @RequestMapping(value = "/import/{entity}/report.csv", method = RequestMethod.POST,
            produces = "text/csv; charset=UTF-8")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<String> report(@PathVariable("entity") String entity,
                                         @RequestBody ImportRequest body) {
        ImportSpec<?> spec = registry.get(entity);
        if (spec == null) return ResponseEntity.notFound().build();
        if (sizeRefusal(body) != null) return ResponseEntity.badRequest().build();

        ImportReport report = engine.dryRun(spec, body.getCsv(), orgId(), userId());

        List<List<?>> rows = new ArrayList<>();
        if (report.getFileError() != null) {
            rows.add(List.of("", "FILE", report.getFileError()));
        } else {
            for (RowResult r : report.getRows())
                rows.add(List.of(String.valueOf(r.getRowNumber()),
                        r.getStatus().name(),
                        r.getMessage() == null ? "" : r.getMessage()));
        }

        String csv = CsvWriter.write(List.of("Row", "Result", "Detail"), rows);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + spec.entity() + "-import-report.csv\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(csv);
    }

    // ── shared refusal ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Server-side size cap. The row cap lives in the spec and is enforced by the reader; this stops a single
     * enormous line reaching the parser at all. A cap the client could raise is not a cap — the same reason
     * O4 clamps {@code ?size=} on the server.
     */
    private String sizeRefusal(ImportRequest body) {
        if (body == null || body.getCsv() == null || body.getCsv().trim().isEmpty())
            return "No file was supplied.";
        if (body.getCsv().length() > MAX_CHARS)
            return "That file is too large. Split it and import the parts separately.";
        return null;
    }
}

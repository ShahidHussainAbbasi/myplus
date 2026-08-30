package com.myplus.common.imports;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The import engine (Template Method): parse → validate → classify → report → write, identical for every
 * entity. What differs per entity is supplied by an {@link ImportSpec}.
 *
 * <h3>Two guarantees, and they are the whole slice</h3>
 * <ol>
 *   <li><b>A dry run writes nothing.</b> {@link #dryRun} reports exactly what {@link #commit} would do and
 *       touches no repository except the one batched existence read.</li>
 *   <li><b>One bad row refuses the WHOLE file.</b> Not a partial commit. Two reasons, and the first is
 *       structural: {@code customer} is a MyISAM table, so a failed batch <i>cannot be rolled back</i> — the
 *       only way to be all-or-nothing is to decide before writing anything. The second is human: a half
 *       imported file leaves the operator unable to tell which half, so their fix-and-retry has to reason
 *       about which rows already exist.</li>
 * </ol>
 *
 * <h3>Why commit re-validates instead of trusting the dry run</h3>
 * The dry run's verdict is a READ, and rows can appear between the two calls. Re-validating is also what lets
 * the commit be one all-or-nothing decision taken entirely before the first insert.
 *
 * <p>Registered by {@code CommonImportAutoConfiguration}, not {@code @Component}: this package sits outside
 * every consumer's component-scan root — the footgun this codebase has already hit twice.
 */
public class ImportEngine {

    /** Headers for the downloadable template — rendered by the caller's CSV writer, not duplicated here. */
    public List<String> templateHeaders(ImportSpec<?> spec) {
        List<String> headers = new ArrayList<>();
        for (ColumnSpec c : spec.columns()) headers.add(c.getHeader());
        return headers;
    }

    /**
     * The template's data rows — deliberately NONE.
     *
     * <p>A sample row is the obvious thing to ship and it is a trap. Whatever it contains, the operator who
     * does not delete it gets one of two bad outcomes: a junk customer called "Irfan Medical Store" imported
     * into their live master, or — if the sample is made deliberately invalid, e.g. a {@code customerType}
     * cell reading {@code WALK_IN | RETAILER | WHOLESALE} — a refusal on their very first attempt, caused by
     * a row the system itself wrote.
     *
     * <p>The guidance it would have carried is delivered where it is actually needed instead: the validators
     * name the expected shape in their refusal (<i>"'licenseExpiry' must be a date as yyyy-MM-dd — got
     * '31-12-2027'"</i>), and because the dry run writes nothing, finding that out costs an operator one
     * click rather than a corrupted master. Each {@link ColumnSpec} still carries its hint; this is about
     * where the hint is shown.
     */
    public List<List<String>> templateRows(ImportSpec<?> spec) {
        return new ArrayList<>();
    }

    /**
     * The finished template file: header row, no data rows (see {@link #templateRows}).
     *
     * <p>Here rather than in each controller so that "what a template looks like" has ONE definition. When
     * only business-service imported anything, the two-line version in its controller was harmless; the
     * moment catalog-service gained a spec it would have become a second copy, and the first divergence
     * would have produced a template one service could generate and the other could not parse.
     */
    public String templateCsv(ImportSpec<?> spec) {
        return CsvWriter.write(templateHeaders(spec), templateRows(spec));
    }

    /** Validate and classify without writing anything. */
    public ImportReport dryRun(ImportSpec<?> spec, String csv, Long orgId, Long userId) {
        Classification c = classify(spec, csv, orgId, userId);
        return c.fileError != null
                ? ImportReport.fileRefused(spec.entity(), c.fileError)
                : ImportReport.of(spec.entity(), c.results, false);
    }

    /**
     * Validate, and write only if EVERY row is acceptable.
     *
     * @return a report whose {@code committed} flag says whether anything was written
     */
    public <T> ImportReport commit(ImportSpec<T> spec, String csv, Long orgId, Long userId) {
        Classification c = classify(spec, csv, orgId, userId);
        if (c.fileError != null)
            return ImportReport.fileRefused(spec.entity(), c.fileError);

        ImportReport report = ImportReport.of(spec.entity(), c.results, false);

        // The central refusal. Note it is checked BEFORE build() is called on anything, so a file with one bad
        // row never reaches the spec's writer at all.
        if (report.hasErrors()) return report;

        List<T> batch = new ArrayList<>();
        for (CsvReader.Row row : c.toCreate) batch.add(spec.build(row, orgId, userId));

        if (!batch.isEmpty()) spec.persist(batch);

        return ImportReport.of(spec.entity(), c.results, true);
    }

    // ── classification ──────────────────────────────────────────────────────────────────────────────────────

    private static final class Classification {
        String fileError;
        List<RowResult> results = new ArrayList<>();
        List<CsvReader.Row> toCreate = new ArrayList<>();

        static Classification refused(String message) {
            Classification c = new Classification();
            c.fileError = message;
            return c;
        }
    }

    private Classification classify(ImportSpec<?> spec, String csv, Long orgId, Long userId) {
        CsvReader.ParsedFile file;
        try {
            file = CsvReader.parse(csv, spec.maxRows());
        } catch (CsvReader.CsvFormatException e) {
            return Classification.refused(e.getMessage());
        }

        String headerError = checkHeaders(spec, file.getHeaders());
        if (headerError != null) return Classification.refused(headerError);

        if (file.getRows().isEmpty())
            return Classification.refused("The file has headers but no data rows.");

        Classification c = new Classification();

        // Pass 1 — per-column validation. Every failing column on a row is reported together: fixing one
        // error, re-uploading and being told about the next one is a bad way to spend an afternoon.
        List<CsvReader.Row> valid = new ArrayList<>();
        for (CsvReader.Row row : file.getRows()) {
            List<String> problems = new ArrayList<>();
            for (ColumnSpec col : spec.columns()) {
                String problem = col.validate(row.get(col.getHeader()));
                if (problem != null) problems.add(problem);
            }
            // Cross-field rules, once every cell has passed on its own. A contradiction between two valid
            // cells is still a row the operator did not mean to write.
            if (problems.isEmpty()) {
                String rowProblem = spec.validateRow(row);
                if (rowProblem != null) problems.add(rowProblem);
            }
            if (problems.isEmpty()) valid.add(row);
            else c.results.add(RowResult.error(row.getLineNumber(), String.join(" ", problems)));
        }

        // Pass 2 — duplicates, in ONE batched read for the whole file (never one query per row).
        Set<String> keys = new LinkedHashSet<>();
        for (CsvReader.Row row : valid) {
            String k = spec.duplicateKey(row);
            if (k != null) keys.add(k);
        }
        Set<String> existing = keys.isEmpty()
                ? new HashSet<>()
                : spec.existingKeys(orgId, userId, keys);

        Set<String> seenInFile = new HashSet<>();
        for (CsvReader.Row row : valid) {
            String k = spec.duplicateKey(row);
            if (k != null && existing.contains(k)) {
                c.results.add(RowResult.skip(row.getLineNumber(), "Already exists — '" + k + "'."));
            } else if (k != null && !seenInFile.add(k)) {
                // An in-file duplicate is reported, never silently collapsed: the operator listed the same
                // customer twice and should know which line was ignored.
                c.results.add(RowResult.skip(row.getLineNumber(),
                        "Appears earlier in this file — '" + k + "'."));
            } else {
                c.results.add(RowResult.create(row.getLineNumber()));
                c.toCreate.add(row);
            }
        }

        c.results.sort((a, b) -> Integer.compare(a.getRowNumber(), b.getRowNumber()));
        return c;
    }

    /**
     * The file's headers must match the template's.
     *
     * <p>An UNKNOWN column refuses the file rather than being ignored. That is deliberate and it is the rule
     * that keeps {@code dueAmount} out: a tenant who adds a balance column and sees "imported successfully"
     * would reasonably believe the balances went in. Being told the column is not accepted is the only honest
     * answer.
     */
    private String checkHeaders(ImportSpec<?> spec, List<String> headers) {
        Set<String> known = new LinkedHashSet<>();
        for (ColumnSpec c : spec.columns()) known.add(c.getHeader().toLowerCase());

        Set<String> seen = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String h : headers) {
            if (h == null || h.isEmpty()) continue;
            if (!known.contains(h.toLowerCase())) unknown.add(h);
            if (!seen.add(h.toLowerCase()))
                return "The column '" + h + "' appears twice. Each column may appear once.";
        }
        if (!unknown.isEmpty())
            return "This file has columns that cannot be imported: " + String.join(", ", unknown)
                    + ". Download the template and use its columns.";

        List<String> missing = new ArrayList<>();
        for (ColumnSpec c : spec.columns())
            if (c.isRequired() && !seen.contains(c.getHeader().toLowerCase())) missing.add(c.getHeader());
        if (!missing.isEmpty())
            return "This file is missing required columns: " + String.join(", ", missing) + ".";

        return null;
    }
}

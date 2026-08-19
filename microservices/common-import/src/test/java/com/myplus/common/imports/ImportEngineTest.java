package com.myplus.common.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice I1 — the engine's guarantees, exercised against a fake spec so the rules are tested and not the entity.
 *
 * <p>The two cases that carry the slice are {@link #one_bad_row_refuses_the_whole_file_and_writes_nothing} and
 * {@link #a_dry_run_never_writes}. Both assert on the SPEC (was persist called, with what) rather than on the
 * returned report — a report is what the engine says it did, and the property under test is what it actually
 * did. This codebase has five recorded incidents of a gate asserting the artefact instead of the property.
 */
class ImportEngineTest {

    /** A minimal spec: two columns, duplicate key = contact, and it records what it was asked to write. */
    private static final class FakeSpec implements ImportSpec<String> {
        final Set<String> alreadyThere = new HashSet<>();
        final List<String> persisted = new ArrayList<>();
        int persistCalls = 0;
        int existingKeyCalls = 0;

        @Override public String entity() { return "fake"; }
        @Override public String label() { return "Fakes"; }

        @Override public List<ColumnSpec> columns() {
            return Arrays.asList(
                    ColumnSpec.text("name", true, 50, "Ali Traders"),
                    ColumnSpec.text("contact", true, 20, "03001234567"),
                    ColumnSpec.number("creditLimit", false, "50000"));
        }

        @Override public String duplicateKey(CsvReader.Row row) {
            String c = row.get("contact");
            return c == null ? null : c.replaceAll("\\s+", "");
        }

        @Override public Set<String> existingKeys(Long orgId, Long userId, Set<String> keys) {
            existingKeyCalls++;
            Set<String> hit = new HashSet<>(keys);
            hit.retainAll(alreadyThere);
            return hit;
        }

        @Override public String build(CsvReader.Row row, Long orgId, Long userId) {
            return row.get("name") + "/" + row.get("contact");
        }

        @Override public int persist(List<String> batch) {
            persistCalls++;
            persisted.addAll(batch);
            return batch.size();
        }

        @Override public int maxRows() { return 50; }
    }

    private FakeSpec spec;
    private ImportEngine engine;

    private static final Long ORG = 1L, USER = 7L;
    private static final String HEADERS = "name,contact,creditLimit\n";

    @BeforeEach
    void setUp() {
        spec = new FakeSpec();
        engine = new ImportEngine();
    }

    // ── the two guarantees ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_dry_run_never_writes() {
        ImportReport r = engine.dryRun(spec, HEADERS + "Ali,0300,100\nSara,0301,\n", ORG, USER);

        assertEquals(2, r.getToCreate());
        assertFalse(r.isCommitted());
        assertEquals(0, spec.persistCalls, "a dry run must not reach the writer at all");
        assertTrue(spec.persisted.isEmpty());
    }

    @Test
    void one_bad_row_refuses_the_whole_file_and_writes_nothing() {
        // Two perfectly good rows and one missing its required contact.
        String csv = HEADERS + "Ali,0300,\nBadRow,,\nSara,0301,\n";

        ImportReport r = engine.commit(spec, csv, ORG, USER);

        assertTrue(r.hasErrors());
        assertFalse(r.isCommitted());
        // THE assertion: the two valid rows were NOT written. "The response said error" passes under a
        // partial commit too, which is exactly the failure this case exists to catch.
        assertEquals(0, spec.persistCalls, "not even the valid rows may be written");
        assertTrue(spec.persisted.isEmpty());
    }

    @Test
    void a_clean_file_is_written_once_as_one_batch() {
        ImportReport r = engine.commit(spec, HEADERS + "Ali,0300,\nSara,0301,\n", ORG, USER);

        assertTrue(r.isCommitted());
        assertEquals(2, r.getToCreate());
        assertEquals(1, spec.persistCalls, "one batch, not one call per row");
        assertEquals(Arrays.asList("Ali/0300", "Sara/0301"), spec.persisted);
    }

    // ── classification ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_existing_key_is_skipped_not_created_and_not_an_error() {
        spec.alreadyThere.add("0300");

        ImportReport r = engine.commit(spec, HEADERS + "Ali,0300,\nSara,0301,\n", ORG, USER);

        assertEquals(1, r.getToCreate());
        assertEquals(1, r.getSkipped());
        assertEquals(0, r.getRefused(), "already existing is not a failure — it is the wanted outcome");
        assertTrue(r.isCommitted());
        assertEquals(List.of("Sara/0301"), spec.persisted);
    }

    @Test
    void re_importing_the_same_file_creates_nothing() {
        String csv = HEADERS + "Ali,0300,\nSara,0301,\n";
        engine.commit(spec, csv, ORG, USER);
        spec.alreadyThere.addAll(Set.of("0300", "0301"));   // as the repository would now report
        spec.persisted.clear();

        ImportReport second = engine.commit(spec, csv, ORG, USER);

        assertEquals(0, second.getToCreate());
        assertEquals(2, second.getSkipped());
        assertTrue(spec.persisted.isEmpty(), "create-only is what makes a re-import safe");
    }

    @Test
    void an_in_file_duplicate_is_reported_not_silently_collapsed() {
        ImportReport r = engine.dryRun(spec, HEADERS + "Ali,0300,\nAli Again,0300,\n", ORG, USER);

        assertEquals(1, r.getToCreate());
        assertEquals(1, r.getSkipped());
        RowResult second = r.getRows().get(1);
        assertEquals(RowResult.Status.SKIP, second.getStatus());
        assertTrue(second.getMessage().contains("Appears earlier"),
                "the operator listed the same contact twice and should be told which line lost");
    }

    @Test
    void the_existence_check_is_ONE_call_for_the_whole_file() {
        StringBuilder sb = new StringBuilder(HEADERS);
        for (int i = 0; i < 30; i++) sb.append("Name").append(i).append(",030").append(i).append(",\n");

        engine.dryRun(spec, sb.toString(), ORG, USER);

        // A per-row check is the O(n^2) shape addCustomer's in-memory full scan already has; an import is
        // where it stops being invisible.
        assertEquals(1, spec.existingKeyCalls);
    }

    @Test
    void every_problem_on_a_row_is_reported_together() {
        ImportReport r = engine.dryRun(spec, HEADERS + ",,notanumber\n", ORG, USER);

        String msg = r.getRows().get(0).getMessage();
        assertTrue(msg.contains("'name'"), msg);
        assertTrue(msg.contains("'contact'"), msg);
        assertTrue(msg.contains("'creditLimit'"), msg);
    }

    // ── file-level refusals ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_unknown_column_refuses_the_file() {
        // The rule that keeps dueAmount out: silently ignoring the column would let an operator believe the
        // balances went in.
        ImportReport r = engine.commit(spec, "name,contact,dueAmount\nAli,0300,5000\n", ORG, USER);

        assertTrue(r.hasErrors());
        assertFalse(r.isCommitted());
        assertTrue(r.getFileError().contains("dueAmount"), r.getFileError());
        assertEquals(0, spec.persistCalls);
    }

    @Test
    void a_missing_required_column_refuses_the_file() {
        ImportReport r = engine.commit(spec, "name\nAli\n", ORG, USER);

        assertTrue(r.getFileError().contains("contact"), r.getFileError());
        assertEquals(0, spec.persistCalls);
    }

    @Test
    void a_repeated_column_refuses_the_file() {
        ImportReport r = engine.dryRun(spec, "name,contact,name\nAli,0300,Ali\n", ORG, USER);

        assertTrue(r.getFileError().contains("twice"), r.getFileError());
    }

    @Test
    void headers_with_no_rows_refuse_the_file() {
        ImportReport r = engine.dryRun(spec, HEADERS, ORG, USER);

        assertTrue(r.hasErrors());
        assertTrue(r.getFileError().contains("no data rows"), r.getFileError());
    }

    @Test
    void a_text_cell_that_a_spreadsheet_would_run_as_a_formula_is_refused() {
        // CsvWriter guards this on the way OUT. This is the same threat on the way IN — refused rather than
        // rewritten, because silently changing a name would break "created exactly as written, or not at all".
        ImportReport r = engine.dryRun(spec, HEADERS + "=cmd|'/c calc'!A1,0300,\n", ORG, USER);

        assertEquals(1, r.getRefused());
        assertTrue(r.getRows().get(0).getMessage().contains("formula"));
    }

    @Test
    void a_negative_number_is_NOT_treated_as_a_formula() {
        // The guard is on TEXT columns only. A leading '-' is a perfectly good negative number, and blanket
        // neutralisation would have broken it.
        ImportReport r = engine.dryRun(spec, HEADERS + "Ali,0300,-250\n", ORG, USER);

        assertEquals(1, r.getToCreate());
        assertEquals(0, r.getRefused());
    }

    // ── the template ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_template_headers_are_the_spec_columns_in_order() {
        // The round-trip contract: one list generates the template AND validates the upload, so the header
        // cannot drift from the parser.
        assertEquals(Arrays.asList("name", "contact", "creditLimit"), engine.templateHeaders(spec));
    }

    @Test
    void the_template_carries_NO_sample_row() {
        // A sample row is the obvious thing to ship and it is a trap: left in place it either imports a junk
        // customer or refuses the operator's first attempt over a row the system wrote itself. The guidance
        // it would have carried lives in the validators' refusal messages, which arrive exactly when needed
        // and cost nothing because the dry run writes nothing.
        assertTrue(engine.templateRows(spec).isEmpty());
    }
}

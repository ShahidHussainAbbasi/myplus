package com.myplus.common.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a dry run or a commit.
 *
 * <p>{@code committed} is what separates the two and is the field every caller should branch on: a dry run
 * reports exactly what a commit would do and returns {@code committed = false}, having written nothing.
 *
 * <p>The counts are held separately from the row list because the preview leads with them (§11) — and because
 * a caller must never have to derive "how many were created" by filtering a list, which is the kind of
 * arithmetic that goes wrong the first time a fourth status appears.
 */
public final class ImportReport {

    private final String entity;
    private final int total;
    private final int toCreate;
    private final int skipped;
    private final int refused;
    private final boolean committed;
    private final List<RowResult> rows;
    /** Set only when the whole file was refused before any row was considered (bad headers, too large). */
    private final String fileError;

    private ImportReport(String entity, int total, int toCreate, int skipped, int refused,
                         boolean committed, List<RowResult> rows, String fileError) {
        this.entity = entity;
        this.total = total;
        this.toCreate = toCreate;
        this.skipped = skipped;
        this.refused = refused;
        this.committed = committed;
        this.rows = rows;
        this.fileError = fileError;
    }

    static ImportReport of(String entity, List<RowResult> rows, boolean committed) {
        int create = 0, skip = 0, error = 0;
        for (RowResult r : rows) {
            switch (r.getStatus()) {
                case CREATE: create++; break;
                case SKIP:   skip++;   break;
                default:     error++;
            }
        }
        return new ImportReport(entity, rows.size(), create, skip, error, committed, rows, null);
    }

    /** The whole file was rejected — nothing was read, nothing was written. */
    static ImportReport fileRefused(String entity, String message) {
        return new ImportReport(entity, 0, 0, 0, 0, false, new ArrayList<>(), message);
    }

    public String getEntity() { return entity; }
    public int getTotal() { return total; }

    /** On a dry run: how many WOULD be created. On a commit: how many WERE. */
    public int getToCreate() { return toCreate; }

    public int getSkipped() { return skipped; }
    public int getRefused() { return refused; }

    /** False for a dry run and for any refused commit — i.e. false whenever nothing was written. */
    public boolean isCommitted() { return committed; }

    public List<RowResult> getRows() { return rows; }
    public String getFileError() { return fileError; }

    /** True when at least one row is unacceptable. A commit refuses the WHOLE file in that case (§4.3). */
    public boolean hasErrors() { return refused > 0 || fileError != null; }
}

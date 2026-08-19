package com.myplus.common.imports;

import java.util.List;
import java.util.Set;

/**
 * What one importable entity contributes to the engine (Strategy).
 *
 * <p>The engine owns parse → validate → classify → report → write. A spec owns only the entity-specific
 * knowledge: which columns, what makes a row a duplicate, how to build the row, and how to save a batch.
 * Adding an entity is therefore a new spec and no engine change.
 *
 * <p><b>Create-only.</b> There is deliberately no {@code update} operation on this interface. A spec cannot
 * express "overwrite the existing row" because the slice's guarantee is that a bad file's worst outcome is
 * rows that were not created (D-2). If update is ever wanted it is a new decision with a new gate, not a
 * method someone adds quietly.
 *
 * @param <T> the entity this spec creates
 */
public interface ImportSpec<T> {

    /** URL-safe identifier: {@code customer}, {@code product}. Also the template's filename stem. */
    String entity();

    /** Human label for the button and the preview title, e.g. {@code Customers}. */
    String label();

    /** The template's columns, in order. The SAME list validates the upload — see {@link ColumnSpec}. */
    List<ColumnSpec> columns();

    /**
     * The value that decides whether this row already exists, normalised (trimmed, case-folded as the entity
     * requires). Null means the row cannot be duplicate-checked and is treated as new.
     */
    String duplicateKey(CsvReader.Row row);

    /**
     * Which of these keys already exist in this tenant.
     *
     * <p><b>Called ONCE per file with every key</b>, never once per row — a per-row check is the O(n²) shape
     * {@code addCustomer}'s in-memory full scan already has, and an import is where it becomes visible.
     */
    Set<String> existingKeys(Long orgId, Long userId, Set<String> keys);

    /** Build the entity from a validated row. Never called for a row the engine classified ERROR or SKIP. */
    T build(CsvReader.Row row, Long orgId, Long userId);

    /** Persist a whole batch. Called at most once per commit, with every CREATE row. */
    int persist(List<T> batch);

    /** Row cap for this entity. The engine enforces it; the spec chooses it. */
    default int maxRows() { return 5000; }
}

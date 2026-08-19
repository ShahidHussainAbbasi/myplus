package com.myplus.common.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every {@link ImportSpec} the service has, keyed by {@link ImportSpec#entity()}.
 *
 * <h3>This registry is what makes the grid button appear</h3>
 * The browser asks which entities are importable and draws the Template/Import buttons only for those. A grid
 * with no spec gets no buttons — so it is structurally impossible to ship a button that posts into a void,
 * which is the inverse of the "capability ships unreachable" failure this codebase has hit repeatedly.
 *
 * <p>It also means adding an entity is exactly one new bean: no controller change, no client change, no list
 * to keep in step in a second file.
 */
public class ImportSpecRegistry {

    private final Map<String, ImportSpec<?>> byEntity = new LinkedHashMap<>();

    public ImportSpecRegistry(List<ImportSpec<?>> specs) {
        if (specs != null)
            for (ImportSpec<?> s : specs) byEntity.put(s.entity().toLowerCase(), s);
    }

    /** The spec for an entity, or null. Callers answer 404 rather than assuming. */
    public ImportSpec<?> get(String entity) {
        return entity == null ? null : byEntity.get(entity.trim().toLowerCase());
    }

    /** Importable entities, as {@code {entity, label}} — what the grid needs to draw its buttons. */
    public List<Map<String, String>> listing() {
        List<Map<String, String>> out = new ArrayList<>();
        for (ImportSpec<?> s : byEntity.values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("entity", s.entity());
            row.put("label", s.label());
            out.add(row);
        }
        return out;
    }
}

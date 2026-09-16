package com.myplus.catalog.service;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CACHE-2 — "these tenants' categories changed".
 *
 * <p>Published by every writer of {@code categories} INSIDE its transaction, and handled by {@link CatalogRefsCache}
 * only AFTER that transaction commits — MySQL first, then the cache, and never on a rollback.
 *
 * <p>The writers (RULE 0 — found by the repository's TYPE, {@code CategoryRepository}: 5 write sites in 3 classes):
 * {@code CategoryService} create, update, delete · {@code ProductService.findOrCreateCategory} (the Product form's
 * free-text category, inside product create/update) · {@code ProductImportSpec.resolveCategory} (CSV import, no
 * transaction of its own — hence the listener's {@code fallbackExecution}). The last two publish only when they
 * CREATE; finding an existing category changes nothing. A new writer must publish this, or the category dropdown keeps
 * the old list until the TTL.
 *
 * <p>More than one org because a write can concern two: the caller's tenant and the category's own. Nulls are dropped.
 */
public record CatalogCategoriesChanged(Set<Long> orgs) {

    public static CatalogCategoriesChanged of(Long... orgs) {
        return new CatalogCategoriesChanged(Arrays.stream(orgs)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet()));
    }
}

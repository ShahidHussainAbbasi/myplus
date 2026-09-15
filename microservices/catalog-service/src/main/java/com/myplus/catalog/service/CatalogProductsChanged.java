package com.myplus.catalog.service;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CACHE-1 — "these tenants' products changed".
 *
 * <p>Published by every writer of {@code products} INSIDE its transaction, and handled by {@link ProductPickerCache}
 * only AFTER that transaction commits — the user's rule: MySQL first, then the cache, and never on a rollback.
 *
 * <p>The writers (RULE 0 — found by the repository's TYPE, not a field name; 9 write sites in 4 classes):
 * {@code ProductService} create, update, setActive, updatePrice (the purchase path), updateClinicalFlags,
 * updateTrackingFlags · {@code ProductDeletionWriter.delete} · {@code ProductImportSpec.persist} (no transaction of its
 * own — hence the listener's {@code fallbackExecution}) · {@code ProductPolicyAdminController.clearTrackingFlags}
 * (a bulk clear, possibly an operator acting on ANOTHER tenant). A new writer must publish this, or the picker keeps
 * serving the old row until the TTL.
 *
 * <p>More than one org because a write can concern two: the caller's tenant (whose pages show the row through the
 * scope's user leg) and the product's own. Nulls are dropped.
 */
public record CatalogProductsChanged(Set<Long> orgs) {

    public static CatalogProductsChanged of(Long... orgs) {
        return new CatalogProductsChanged(Arrays.stream(orgs)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet()));
    }
}

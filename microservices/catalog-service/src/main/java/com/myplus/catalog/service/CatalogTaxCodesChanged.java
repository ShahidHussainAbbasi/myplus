package com.myplus.catalog.service;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CACHE-2 — "these tenants' tax codes changed".
 *
 * <p>Published by every writer of {@code tax_code} INSIDE its transaction, and handled by {@link CatalogRefsCache}
 * only AFTER that transaction commits — MySQL first, then the cache, and never on a rollback.
 *
 * <p>The writers (RULE 0 — found by the repository's TYPE, {@code TaxCodeRepository}: 4 write sites, all in
 * {@code TaxCodeService}): create, update, delete, and {@code apply}'s single-default rule, which clears the flag on
 * this org's other codes inside the same create/update transaction — so the create/update publish covers it.
 *
 * <p>More than one org because a write can concern two: the caller's tenant and the code's own. Nulls are dropped.
 */
public record CatalogTaxCodesChanged(Set<Long> orgs) {

    public static CatalogTaxCodesChanged of(Long... orgs) {
        return new CatalogTaxCodesChanged(Arrays.stream(orgs)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet()));
    }
}

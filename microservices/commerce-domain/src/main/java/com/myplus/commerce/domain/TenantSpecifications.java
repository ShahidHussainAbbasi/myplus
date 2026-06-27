package com.myplus.commerce.domain;

import org.springframework.data.jpa.domain.Specification;

/**
 * The tenant-scoping read contract, as a reusable JPA {@link Specification} (slice 33, Phase 2).
 *
 * <p>Encodes the standard NULL-fallback rule used across every org-scoped service
 * (see ARCHITECTURE-MULTITENANCY): a caller sees their organization's rows, plus their own pre-migration
 * rows that were written before {@code organization_id} existed (org IS NULL AND user = caller).
 *
 * <p>Existing services hand-write this as JPQL {@code @Query} strings per entity; new entities
 * (catalog/trade/pharma) should compose this Specification instead of re-deriving the predicate.
 * Requires the entity to expose {@code organizationId} and {@code userId} attributes (the project convention).
 */
public final class TenantSpecifications {

    private TenantSpecifications() {}

    /** {@code organizationId = :orgId OR (organizationId IS NULL AND userId = :userId)}. */
    public static <T> Specification<T> scoped(Long orgId, Long userId) {
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("organizationId"), orgId),
                cb.and(cb.isNull(root.get("organizationId")), cb.equal(root.get("userId"), userId)));
    }

    /** Own rows only (role-aware visibility): {@code userId = :userId AND (organizationId = :orgId OR organizationId IS NULL)}. */
    public static <T> Specification<T> ownScoped(Long orgId, Long userId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("userId"), userId),
                cb.or(cb.equal(root.get("organizationId"), orgId), cb.isNull(root.get("organizationId"))));
    }
}

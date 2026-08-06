package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped orders (E1, slice 46), NULL-fallback. */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    String SCOPE = "(o.organizationId = :orgId OR (o.organizationId IS NULL AND o.userId = :userId))";

    @Query("SELECT o FROM Order o WHERE " + SCOPE + " ORDER BY o.createdAt DESC")
    List<Order> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT o FROM Order o WHERE o.id = :id AND " + SCOPE)
    Optional<Order> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** A storefront shopper's own orders, newest first (slice 61, My Orders). */
    List<Order> findByCustomerAccountIdOrderByCreatedAtDesc(Long customerAccountId);

    // OMS O1: `findPendingReservations` is DELETED with OrderSagaRecoveryRelay. Marketplace no longer holds
    // reservations — business-service's sale path reserves, confirms and re-drives its own recovery, so there is
    // no half-finished marketplace saga left to sweep up.

    /**
     * OMS O1 reconciliation: orders that never reached the books.
     *
     * <p>Every storefront order placed BEFORE O1 produced stock movement and possibly a card charge, but no
     * invoice — so it contributed nothing to the P&amp;L, tax register or AR. Those rows are stamped
     * {@code LEGACY_UNPOSTED} and are deliberately NOT back-posted: writing revenue at their original dates
     * would post into closed accounting periods. This read is how an operator finds them and decides what to do
     * (raise a manual invoice, or accept and annotate).
     *
     * <p>Scoped like every other read — one tenant's backlog is not another's business.
     */
    @Query("SELECT o FROM Order o WHERE o.booksStatus = :booksStatus AND " + SCOPE + " ORDER BY o.createdAt DESC")
    List<Order> findByBooksStatusScoped(@Param("booksStatus") String booksStatus,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);
}

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

    /**
     * OMS O4 — the back-office list: scoped, filtered and PAGED (fixes OMS-7).
     *
     * <p>{@link #findScoped} above returns every order the tenant has ever taken. That is fine for the internal
     * callers that still need the whole set (reconciliation counts), and unusable for a screen: a merchant with
     * 20 000 orders was loading 20 000 rows and 20 000 DTOs to look at the newest 25.
     *
     * <p><b>Every filter is applied HERE, in the query.</b> Filtering a fetched page in Java would page the wrong
     * thing — 25 rows read, then narrowed to 3 — and the operator would page forward through mostly-empty
     * screens looking for orders that were never fetched.
     *
     * <p>Each filter is null-guarded in the predicate itself ({@code :x IS NULL OR col = :x}) so one query serves
     * every combination. The alternative — Specifications or a hand-built JPQL string — buys dynamic SQL at the
     * cost of a query no one can read, for eight optional parameters.
     *
     * <p>The text search is lower-cased on both sides and spans the four identifiers a merchant has in front of
     * them when a customer telephones: order number, invoice number, name, contact.
     *
     * <p>Sorted by {@code created_at DESC} to match {@code idx_orders_org_created} (Flyway V13), so the LIMIT
     * stops early instead of sorting the tenant's whole history. Sorting is fixed rather than caller-supplied for
     * that reason: an arbitrary sort column would silently drop off the index.
     */
    @Query("""
            SELECT o FROM Order o
             WHERE (o.organizationId = :orgId OR (o.organizationId IS NULL AND o.userId = :userId))
               AND (:status IS NULL OR o.fulfilmentStatus = :status)
               AND (:paymentStatus IS NULL OR o.paymentStatus = :paymentStatus)
               AND (:source IS NULL OR o.source = :source)
               AND (:from IS NULL OR o.createdAt >= :from)
               AND (:to IS NULL OR o.createdAt <= :to)
               AND (:like IS NULL
                    OR LOWER(o.orderNo)         LIKE :like
                    OR LOWER(o.invoiceNo)       LIKE :like
                    OR LOWER(o.customerName)    LIKE :like
                    OR LOWER(o.customerContact) LIKE :like)
               AND (:today IS NULL
                    OR (o.promisedDate IS NOT NULL AND o.promisedDate < :today
                        AND o.fulfilmentStatus NOT IN (
                            com.myplus.marketplace.entity.FulfilmentStatus.SHIPPED,
                            com.myplus.marketplace.entity.FulfilmentStatus.DELIVERED,
                            com.myplus.marketplace.entity.FulfilmentStatus.CANCELLED,
                            com.myplus.marketplace.entity.FulfilmentStatus.RETURNED)))
               AND (:bookedBy IS NULL OR o.bookedByUserId = :bookedBy)
             ORDER BY o.createdAt DESC
            """)
    org.springframework.data.domain.Page<Order> findPage(
            @Param("orgId") Long orgId,
            @Param("userId") Long userId,
            @Param("status") com.myplus.marketplace.entity.FulfilmentStatus status,
            @Param("paymentStatus") String paymentStatus,
            @Param("source") String source,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("like") String like,
            /** OMS O5c — non-null narrows to LATE orders (promised before this date and not complete). */
            @Param("today") java.time.LocalDate today,
            /**
             * OMS O7 D2 — non-null narrows to one rep's own orders, served by
             * {@code idx_orders_org_booker_created} (V19).
             *
             * <p>This is what lets a booker see what happened to the orders they took — the founding
             * requirement's *"after confirm or reject the status should be visible to the order booker"*. It is
             * a FILTER, not a security boundary: org scoping above is what keeps tenants apart, and this narrows
             * within a tenant the caller can already see.
             */
            @Param("bookedBy") Long bookedBy,
            org.springframework.data.domain.Pageable pageable);

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
     *
     * <p>PAGED since the 2026-08-10 review (R5). O4 closed OMS-7 on the back-office list and left this one
     * returning every matching row. It is the smaller risk of the two — the {@code LEGACY_UNPOSTED} backlog is
     * a fixed historical set that only shrinks — but {@code ?booksStatus=POSTED} points the very same query at
     * every order the tenant has ever booked, which grows forever. Served by
     * {@code idx_orders_books_status (organization_id, books_status)} from V10.
     *
     * <p>The unpaged {@code List} form was deleted with it, for R4's reason: a callerless repository method
     * that returns an unbounded scoped read is not spare capacity, it is the next person's reintroduction of
     * OMS-7 with no new SQL written.
     */
    @Query("SELECT o FROM Order o WHERE o.booksStatus = :booksStatus AND " + SCOPE + " ORDER BY o.createdAt DESC")
    org.springframework.data.domain.Page<Order> pageByBooksStatusScoped(
            @Param("booksStatus") String booksStatus,
            @Param("orgId") Long orgId, @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable);

    // ── OMS O2 — identity, idempotency ────────────────────────────────────────────────────────────────────

    /**
     * Next per-org order number. MAX+1 inside the creating transaction, made safe by
     * UNIQUE(organization_id, order_seq). COALESCE so the first order in a new org starts at 1.
     */
    @Query("SELECT COALESCE(MAX(o.orderSeq), 0) FROM Order o WHERE o.organizationId = :orgId")
    long maxOrderSeqForOrg(@Param("orgId") Long orgId);

    /**
     * Public tracking (OMS-8): resolve by the MERCHANT-FACING number, not the primary key. Deliberately not
     * org-scoped — a guest tracking an order has no tenant identity — but the number is per-org and paired with
     * a contact check, so it is not enumerable the way a global auto-increment id was.
     */
    /**
     * <b>A List, not an Optional.</b> {@code order_no} is unique only PER ORG — the constraint is
     * {@code UNIQUE(organization_id, order_seq)} and {@code idx_orders_order_no} is a plain index — so every
     * tenant's first order is {@code SO-000001}. The original {@code Optional} form threw
     * <i>"Query did not return a unique result: 2 results were returned"</i> the moment a second tenant placed
     * an order, breaking public tracking for everyone. The caller disambiguates on the contact it already has
     * to check anyway.
     */
    java.util.List<Order> findAllByOrderNo(String orderNo);

    /**
     * OMS O5c — orders that are still owed something.
     *
     * <p>Live states only: a CANCELLED or RETURNED order may still carry backordered lines historically, and
     * listing them as outstanding would have an operator chasing goods nobody is waiting for.
     *
     * <p>PAGED since the 2026-08-10 review (R5) — and the unpaged form deleted with it, per R4. This is the one
     * of the two OMS-7 stragglers that actually grows: reconciliation is a historical set, but a shop's
     * backorder book grows with its trade, and a busy shop's is exactly the one that must not be loaded whole.
     * Oldest promise first, so page 1 is the work that is most overdue.
     *
     * <p>{@code countQuery} is given explicitly because the {@code JOIN o.items} would otherwise make Spring
     * derive a count that multiplies each order by its backordered lines — a two-line order would be counted
     * twice and {@code totalElements} would exceed the rows that exist.
     */
    @Query(value = "SELECT DISTINCT o FROM Order o JOIN o.items i WHERE i.quantityBackordered > 0 AND " + SCOPE
            + " AND o.fulfilmentStatus NOT IN ("
            + "   com.myplus.marketplace.entity.FulfilmentStatus.CANCELLED,"
            + "   com.myplus.marketplace.entity.FulfilmentStatus.RETURNED) "
            + " ORDER BY o.promisedDate ASC, o.createdAt ASC",
            countQuery = "SELECT COUNT(DISTINCT o) FROM Order o JOIN o.items i WHERE i.quantityBackordered > 0 AND "
            + SCOPE
            + " AND o.fulfilmentStatus NOT IN ("
            + "   com.myplus.marketplace.entity.FulfilmentStatus.CANCELLED,"
            + "   com.myplus.marketplace.entity.FulfilmentStatus.RETURNED)")
    org.springframework.data.domain.Page<Order> pageOutstandingBackorders(
            @Param("orgId") Long orgId, @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable);

    /** OMS-3: has this checkout already produced an order? Same key the SALE deduplicates on. */
    @Query("SELECT o FROM Order o WHERE o.organizationId = :orgId AND o.idempotencyKey = :key")
    Optional<Order> findByOrgAndIdempotencyKey(@Param("orgId") Long orgId, @Param("key") String key);
}

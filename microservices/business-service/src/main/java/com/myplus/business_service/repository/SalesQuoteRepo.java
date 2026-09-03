package com.myplus.business_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.business_service.entity.SalesQuote;

/** B2B-P4b — sales quotes, tenant-scoped with the standard NULL-fallback (org rows + caller's legacy rows). */
@Repository
public interface SalesQuoteRepo extends JpaRepository<SalesQuote, Long> {

    String SCOPE = "(q.organizationId = :orgId OR (q.organizationId IS NULL AND q.userId = :userId))";

    @Query("SELECT q FROM SalesQuote q WHERE " + SCOPE + " ORDER BY q.id DESC")
    List<SalesQuote> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR read: a quote from another tenant is indistinguishable from one that does not exist. */
    /**
     * #27 — the caller's OWN quotes.
     *
     * <p>A quote is a rep's working document: it records what discount they offered which account, before any
     * of it is agreed. {@link #findScoped} is TENANT-wide, so every user with {@code dealerPricing} could read
     * every rep's pricing. This is the same "own rows" narrowing {@code SellRepo.findOwnScoped} already
     * applies to sales — the policy existed, this screen was simply never brought under it.
     */
    @Query("SELECT q FROM SalesQuote q WHERE q.userId = :userId AND " + SCOPE + " ORDER BY q.id DESC")
    List<SalesQuote> findOwnScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * #27 — one quote by id, only if the caller RAISED it.
     *
     * <p>Separate from {@link #findByIdScoped} deliberately. Filtering a list while leaving the by-id read
     * open is not a restriction — ids are sequential and anyone can count. The predicate has to be in every
     * read, not the one that happens to render a screen.
     */
    @Query("SELECT q FROM SalesQuote q WHERE q.id = :id AND q.userId = :userId AND " + SCOPE)
    Optional<SalesQuote> findOwnByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                           @Param("userId") Long userId);

    @Query("SELECT q FROM SalesQuote q WHERE q.id = :id AND " + SCOPE)
    Optional<SalesQuote> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Next per-org quote number. MAX+1 inside the creating transaction, made safe by UNIQUE(organization_id,
     * quote_seq) — the same allocation invoice_seq and credit_note_seq use. COALESCE so the first quote in a new
     * org starts at 1 rather than tripping over a null.
     */
    @Query("SELECT COALESCE(MAX(q.quoteSeq), 0) FROM SalesQuote q WHERE q.organizationId = :orgId")
    long maxQuoteSeqForOrg(@Param("orgId") Long orgId);
}

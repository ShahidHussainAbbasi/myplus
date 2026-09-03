package com.myplus.business_service.repository;

import com.myplus.business_service.entity.PurchaseReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Debit notes — supplier return documents (slice b2b-P3c). */
@Repository
public interface PurchaseReturnRepo extends JpaRepository<PurchaseReturn, Long> {

    /**
     * Task #15: ONE debit note by its own number, tenant-scoped — the row a printable document is built from.
     *
     * <p>Keyed on the note number because that is the document's identity: it is what the operator sees after
     * taking a return and what the SUPPLIER reconciles against. {@code UNIQUE(organization_id, debit_note_seq)}
     * makes it unique within a tenant.
     *
     * <p>Safety is the SCOPE PREDICATE, not an unguessable key — a row id is just as guessable as
     * {@code DBN-000012}. No userId fallback here, unlike the sale side, and that is correct rather than an
     * omission: V33 CREATED this table, so no pre-migration org-NULL rows exist for a fallback to rescue.
     */
    @Query("SELECT r FROM PurchaseReturn r WHERE r.debitNoteNo = :noteNo AND r.organizationId = :orgId")
    java.util.Optional<PurchaseReturn> findByDebitNoteNoScoped(@Param("noteNo") String noteNo,
            @Param("orgId") Long orgId);

    /**
     * Next debit-note sequence for this org. MAX+1 inside the return's transaction, exactly as invoice
     * numbers have worked since slice 22 — {@code UNIQUE(organization_id, debit_note_seq)} is what actually
     * guarantees two concurrent returns cannot commit the same number.
     */
    @Query("SELECT COALESCE(MAX(r.debitNoteSeq), 0) FROM PurchaseReturn r WHERE r.organizationId = :orgId")
    long maxDebitNoteSeqForOrg(@Param("orgId") Long orgId);

    /** Org-scoped list, newest first — every read here is tenant-scoped. */
    @Query("SELECT r FROM PurchaseReturn r WHERE r.organizationId = :orgId ORDER BY r.id DESC")
    List<PurchaseReturn> findScoped(@Param("orgId") Long orgId);

    /**
     * B2B-P3f: one vendor's debit notes, for the statement's DEBIT_NOTE lines.
     *
     * <p>Unlike the sale side there is no cutover filter: {@code amount} has been persisted since 3c, so
     * every debit note — including historical ones — has a value and can be shown. This is why V34 back-fills
     * {@code purchase.issued_total} for AP and cannot for AR.
     *
     * <p>Uses the org + user NULL-fallback the statement's other reads use, so a legacy row with no
     * organizationId stays visible to the user who owns it.
     */
    @Query("SELECT r FROM PurchaseReturn r WHERE r.venderId = :venderId "
         + "AND (r.organizationId = :orgId OR (r.organizationId IS NULL AND r.userId = :userId))")
    List<PurchaseReturn> findDebitNotesForVender(@Param("venderId") Long venderId,
            @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * #24 — the debit register, narrowed by supplier, product and date in ONE query.
     *
     * <p>Supersedes {@link #findDebitNotesForVender} for the register read: that one answers only the
     * supplier question, and adding date and product beside it would have meant four queries for the four
     * combinations. Nullable parameters keep it to one, so every filtered and unfiltered read applies the
     * same scope predicate.
     *
     * <p>Unlike the sale side, the party IS on the row here ({@code venderId}), which is why the supplier
     * filter is SQL and the customer filter cannot be.
     *
     * <p>⚠ {@code to} must already be the END of its day — see {@code SaleReturnRepo.findScopedFiltered}.
     */
    @Query("SELECT r FROM PurchaseReturn r WHERE "
         + "(r.organizationId = :orgId OR (r.organizationId IS NULL AND r.userId = :userId)) "
         + "AND (:venderId IS NULL OR r.venderId = :venderId) "
         + "AND (:productId IS NULL OR r.productId = :productId) "
         + "AND (:from IS NULL OR r.dated >= :from) "
         + "AND (:to IS NULL OR r.dated <= :to) "
         + "ORDER BY r.dated DESC")
    List<PurchaseReturn> findDebitNotesFiltered(@Param("orgId") Long orgId, @Param("userId") Long userId,
            @Param("venderId") Long venderId, @Param("productId") Long productId,
            @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);
}

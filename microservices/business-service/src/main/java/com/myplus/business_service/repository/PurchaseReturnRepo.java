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
     * Next debit-note sequence for this org. MAX+1 inside the return's transaction, exactly as invoice
     * numbers have worked since slice 22 — {@code UNIQUE(organization_id, debit_note_seq)} is what actually
     * guarantees two concurrent returns cannot commit the same number.
     */
    @Query("SELECT COALESCE(MAX(r.debitNoteSeq), 0) FROM PurchaseReturn r WHERE r.organizationId = :orgId")
    long maxDebitNoteSeqForOrg(@Param("orgId") Long orgId);

    /** Org-scoped list, newest first — every read here is tenant-scoped. */
    @Query("SELECT r FROM PurchaseReturn r WHERE r.organizationId = :orgId ORDER BY r.id DESC")
    List<PurchaseReturn> findScoped(@Param("orgId") Long orgId);
}

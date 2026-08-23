package com.myplus.business_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.OrgDocumentSeq;
import com.myplus.business_service.entity.OrgDocumentSeqId;

/**
 * The per-org document counters. Every query here is NATIVE and deliberately so.
 *
 * <p>JPA would read the row into the persistence context, increment the entity and flush on commit — a
 * read-modify-write with the read taking no lock, which is precisely the {@code MAX(seq) + 1} race in a
 * different costume. The serialisation has to happen in the database, in one statement, so the row lock
 * exists for the whole increment.
 */
public interface OrgDocumentSeqRepo extends JpaRepository<OrgDocumentSeq, OrgDocumentSeqId> {

    /**
     * Take the next number.
     *
     * <p><b>This runs FIRST, before any insert, and the ordering is not stylistic.</b> The obvious shape —
     * {@code INSERT IGNORE} the row and then {@code UPDATE} it — deadlocks under real contention, and did:
     * on a duplicate key the insert takes a SHARED lock on the existing row, the update then needs an
     * EXCLUSIVE one, and concurrent callers deadlock upgrading against each other. MySQL kills one with
     * "Deadlock found when trying to get lock", which is the failure this whole table exists to remove.
     *
     * <p>Updating first takes the exclusive lock outright, so there is no upgrade to deadlock on. The insert
     * below is then only reached by a tenant that has genuinely never issued this document type.
     *
     * @return rows affected — 0 means the counter does not exist yet
     */
    @Modifying
    @Query(value = "UPDATE org_document_seq SET next_val = next_val + 1, updated = NOW() "
                 + "WHERE organization_id = :orgId AND doc_type = :docType", nativeQuery = true)
    int bump(@Param("orgId") Long orgId, @Param("docType") String docType);

    /**
     * Create this tenant's counter at ZERO. Allocates nothing.
     *
     * <p>{@code INSERT IGNORE}, and called from a SEPARATE committed transaction — see
     * {@code DocumentNumberService.ensureCounter}. Creating the row inside the allocating transaction is what
     * caused the second deadlock: when a tenant has no counter yet, EVERY concurrent caller finds nothing to
     * update and they all try to insert the same primary key at once, and the duplicate-key failures deadlock
     * against each other.
     *
     * <p>Because it sets zero rather than one, it can commit on its own without giving anybody a number. The
     * number still comes only from {@link #bump}, inside the caller's transaction, so the numbering stays
     * gapless.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated) "
                 + "VALUES (:orgId, :docType, 0, NOW())", nativeQuery = true)
    void createCounterAtZero(@Param("orgId") Long orgId, @Param("docType") String docType);

    /** Read back what {@link #bump} just allocated, in the same transaction and behind the same lock. */
    @Query(value = "SELECT next_val FROM org_document_seq "
                 + "WHERE organization_id = :orgId AND doc_type = :docType", nativeQuery = true)
    Long current(@Param("orgId") Long orgId, @Param("docType") String docType);
}

package com.myplus.finance.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.finance.entity.OrgDocumentSeq;
import com.myplus.finance.entity.OrgDocumentSeqId;

/**
 * DOC-INT B — finance's per-org receipt counters. Every query is NATIVE, deliberately.
 *
 * <p>A JPA load-increment-flush reads the row without a lock, which is the {@code COUNT + 1} race in a different
 * costume. The serialisation has to happen in the database, in one statement, so the row lock covers the whole
 * increment. Same statements as business-service's {@code OrgDocumentSeqRepo}, whose javadoc records the two
 * deadlocks that fixed their order.
 */
public interface OrgDocumentSeqRepo extends JpaRepository<OrgDocumentSeq, OrgDocumentSeqId> {

    /** Take the next number. Takes an exclusive row lock held until the caller commits. @return rows affected */
    @Modifying
    @Query(value = "UPDATE org_document_seq SET next_val = next_val + 1, updated = NOW() "
                 + "WHERE organization_id = :orgId AND doc_type = :docType", nativeQuery = true)
    int bump(@Param("orgId") Long orgId, @Param("docType") String docType);

    /** Create this tenant's counter at ZERO, allocating nothing. Called in its own committed transaction. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated) "
                 + "VALUES (:orgId, :docType, 0, NOW())", nativeQuery = true)
    void createCounterAtZero(@Param("orgId") Long orgId, @Param("docType") String docType);

    /** The counter's value — after {@link #bump}, the number just allocated, read behind the same lock. */
    @Query(value = "SELECT next_val FROM org_document_seq "
                 + "WHERE organization_id = :orgId AND doc_type = :docType", nativeQuery = true)
    Long current(@Param("orgId") Long orgId, @Param("docType") String docType);
}

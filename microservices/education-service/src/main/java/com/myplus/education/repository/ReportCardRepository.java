package com.myplus.education.repository;

import com.myplus.education.entity.ReportCard;
import com.myplus.education.entity.ReportCardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportCardRepository extends JpaRepository<ReportCard, Long> {

    /**
     * One student's issued cards, newest term first — the transcript read (D6).
     * SUPERSEDED and WITHDRAWN rows are included deliberately; the caller decides what to show, because
     * "what did we issue and when" is exactly the question versioning exists to answer.
     */
    @Query("select c from ReportCard c where (c.organizationId = :orgId "
            + "or (c.organizationId is null and c.userId = :userId)) "
            + "and c.studentEnrollNo = :enrollNo order by c.termId desc, c.version desc")
    List<ReportCard> findByStudentScoped(@Param("enrollNo") String enrollNo,
                                         @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Every card issued for one term — the class view, and the input to a batch print. */
    @Query("select c from ReportCard c where (c.organizationId = :orgId "
            + "or (c.organizationId is null and c.userId = :userId)) "
            + "and c.termId = :termId order by c.studentEnrollNo, c.version desc")
    List<ReportCard> findByTermScoped(@Param("termId") Long termId,
                                      @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * The CURRENT card for a student in a term, if one has been issued. There can be at most one
     * PUBLISHED row per (student, term) — republishing supersedes the previous (D5).
     */
    @Query("select c from ReportCard c where (c.organizationId = :orgId "
            + "or (c.organizationId is null and c.userId = :userId)) "
            + "and c.studentEnrollNo = :enrollNo and c.termId = :termId and c.status = :status")
    Optional<ReportCard> findCurrentScoped(@Param("enrollNo") String enrollNo, @Param("termId") Long termId,
                                           @Param("status") ReportCardStatus status,
                                           @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Highest version issued so far for this student+term; the next publish is this + 1. */
    @Query("select coalesce(max(c.version), 0) from ReportCard c where (c.organizationId = :orgId "
            + "or (c.organizationId is null and c.userId = :userId)) "
            + "and c.studentEnrollNo = :enrollNo and c.termId = :termId")
    int maxVersionScoped(@Param("enrollNo") String enrollNo, @Param("termId") Long termId,
                         @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE card by a client-supplied id within the caller's tenant. */
    @Query("select c from ReportCard c where c.id = :id and (c.organizationId = :orgId "
            + "or (c.organizationId is null and c.userId = :userId))")
    Optional<ReportCard> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                        @Param("userId") Long userId);
}

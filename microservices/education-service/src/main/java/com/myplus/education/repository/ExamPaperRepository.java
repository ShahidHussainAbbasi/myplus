package com.myplus.education.repository;

import com.myplus.education.entity.ExamPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamPaperRepository extends JpaRepository<ExamPaper, Long> {

    /** The papers of one exam, in datesheet order — that is how a school reads it. */
    @Query("select p from ExamPaper p where p.examId = :examId and (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId)) "
            + "order by p.examDate, p.timeFrom")
    List<ExamPaper> findByExamScoped(@Param("examId") Long examId, @Param("orgId") Long orgId,
                                     @Param("userId") Long userId);

    /** All papers for the tenant, for the datesheet view (filtered by class in the service). */
    @Query("select p from ExamPaper p where p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId) order by p.examDate, p.timeFrom")
    List<ExamPaper> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Slice 1.5 (D8) — the papers of SEVERAL exams in one query, for a whole term's report cards. */
    @Query("select p from ExamPaper p where p.examId in :examIds and (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId)) "
            + "order by p.examDate, p.timeFrom")
    List<ExamPaper> findByExamIdsScoped(@Param("examIds") java.util.Collection<Long> examIds,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Slice 1.5 (D8) — resolve MANY papers by id in one query, still tenant-scoped. */
    @Query("select p from ExamPaper p where p.id in :ids and (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId))")
    List<ExamPaper> findByIdsScoped(@Param("ids") java.util.Collection<Long> ids,
                                    @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE paper by a client-supplied id within the caller's tenant. */
    @Query("select p from ExamPaper p where p.id = :id and (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId))")
    Optional<ExamPaper> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                       @Param("userId") Long userId);

    /**
     * Cascade for exam deletion (test 10: no orphans). Scoped in the query itself so a crafted exam id
     * can never delete another tenant's papers.
     */
    @Modifying
    @Query("delete from ExamPaper p where p.examId = :examId and (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId))")
    int deleteByExamScoped(@Param("examId") Long examId, @Param("orgId") Long orgId,
                           @Param("userId") Long userId);

    long countByExamId(Long examId);
}

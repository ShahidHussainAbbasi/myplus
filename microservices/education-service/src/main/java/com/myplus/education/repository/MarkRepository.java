package com.myplus.education.repository;

import com.myplus.education.entity.Mark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarkRepository extends JpaRepository<Mark, Long> {

    /** Every mark on one paper — the marksheet read. */
    @Query("select m from Mark m where m.examPaperId = :paperId and (m.organizationId = :orgId "
            + "or (m.organizationId is null and m.userId = :userId))")
    List<Mark> findByPaperScoped(@Param("paperId") Long paperId, @Param("orgId") Long orgId,
                                 @Param("userId") Long userId);

    /** One student's marks across every paper — what 1.5's transcript will read. */
    @Query("select m from Mark m where m.studentEnrollNo = :enrollNo and (m.organizationId = :orgId "
            + "or (m.organizationId is null and m.userId = :userId))")
    List<Mark> findByStudentScoped(@Param("enrollNo") String enrollNo, @Param("orgId") Long orgId,
                                   @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE mark by a client-supplied id within the caller's tenant. */
    @Query("select m from Mark m where m.id = :id and (m.organizationId = :orgId "
            + "or (m.organizationId is null and m.userId = :userId))")
    Optional<Mark> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                  @Param("userId") Long userId);

    /** Does this paper have ANY mark yet? Drives the first-mark lock (D4) without loading the rows. */
    long countByExamPaperId(Long examPaperId);
}

package com.myplus.education.repository;

import com.myplus.education.entity.HomeworkSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface HomeworkSubmissionRepository extends JpaRepository<HomeworkSubmission, Long> {

    /**
     * The rows that EXIST for one task — the mark sheet is this joined onto the roster (D2).
     *
     * Deliberately not "the roster with submissions": rows are created lazily, so a student with nothing
     * recorded simply has no row here, and the caller supplies the roster from StudentVisibilityService.
     */
    @Query("select s from HomeworkSubmission s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) and s.homeworkId = :homeworkId")
    List<HomeworkSubmission> findByHomeworkScoped(@Param("homeworkId") Long homeworkId,
                                                  @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Rows across SEVERAL tasks in one query — the list screen's completion counts (D3b). */
    @Query("select s from HomeworkSubmission s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) and s.homeworkId in :homeworkIds")
    List<HomeworkSubmission> findByHomeworkIdsScoped(@Param("homeworkIds") Collection<Long> homeworkIds,
                                                     @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** One student's row for one task, if it exists — the UNIQUE key makes this at most one. */
    @Query("select s from HomeworkSubmission s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) "
            + "and s.homeworkId = :homeworkId and s.studentEnrollNo = :enrollNo")
    Optional<HomeworkSubmission> findOneScoped(@Param("homeworkId") Long homeworkId,
                                               @Param("enrollNo") String enrollNo,
                                               @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE submission by a client-supplied id within the caller's tenant. */
    @Query("select s from HomeworkSubmission s where s.id = :id and (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId))")
    Optional<HomeworkSubmission> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                                @Param("userId") Long userId);
}

package com.myplus.education.repository;

import com.myplus.education.entity.Promotion;
import com.myplus.education.entity.PromotionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    /** One student's progression across years, newest first — the "where has this child been?" read. */
    @Query("select p from Promotion p where (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId)) "
            + "and p.studentEnrollNo = :enrollNo order by p.academicYearId desc, p.id desc")
    List<Promotion> findByStudentScoped(@Param("enrollNo") String enrollNo,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Everything decided in one year — the history view, and what a re-run has to reckon with. */
    @Query("select p from Promotion p where (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId)) "
            + "and p.academicYearId = :yearId order by p.fromGradeName, p.studentEnrollNo")
    List<Promotion> findByYearScoped(@Param("yearId") Long yearId,
                                     @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Existing decisions for a set of students in one year, in ONE query.
     *
     * The plan screen must show a whole class at once; asking per student would be the N+1 that 1.5 D8
     * had to undo elsewhere. Includes REVERSED rows deliberately — the caller needs to know a reversed
     * decision exists before offering to make a new one.
     */
    @Query("select p from Promotion p where (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId)) "
            + "and p.academicYearId = :yearId and p.studentEnrollNo in :enrollNos")
    List<Promotion> findByYearAndStudentsScoped(@Param("yearId") Long yearId,
                                                @Param("enrollNos") Collection<String> enrollNos,
                                                @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** The live decision for one student in one year, if any (D6's uniqueness makes this at most one). */
    @Query("select p from Promotion p where (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId)) "
            + "and p.academicYearId = :yearId and p.studentEnrollNo = :enrollNo and p.status = :status")
    Optional<Promotion> findLiveScoped(@Param("enrollNo") String enrollNo, @Param("yearId") Long yearId,
                                       @Param("status") PromotionStatus status,
                                       @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE promotion by a client-supplied id within the caller's tenant. */
    @Query("select p from Promotion p where p.id = :id and (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId))")
    Optional<Promotion> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                       @Param("userId") Long userId);
}

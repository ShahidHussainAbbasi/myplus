package com.myplus.education.repository;

import com.myplus.education.entity.Exam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamRepository extends JpaRepository<Exam, Long> {

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. */
    @Query("select e from Exam e where e.organizationId = :orgId "
            + "or (e.organizationId is null and e.userId = :userId) order by e.dated desc")
    List<Exam> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Every exam of one term — the read 1.4/1.5 run constantly, and what the weight total sums over. */
    @Query("select e from Exam e where e.termId = :termId and (e.organizationId = :orgId "
            + "or (e.organizationId is null and e.userId = :userId))")
    List<Exam> findByTermScoped(@Param("termId") Long termId, @Param("orgId") Long orgId,
                                @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE exam by a client-supplied id within the caller's tenant. */
    @Query("select e from Exam e where e.id = :id and (e.organizationId = :orgId "
            + "or (e.organizationId is null and e.userId = :userId))")
    Optional<Exam> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                  @Param("userId") Long userId);
}

package com.myplus.education.repository;

import com.myplus.education.entity.Grade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GradeRepository extends JpaRepository<Grade, Long> {
    Page<Grade> findByUserId(Long userId, Pageable pageable);
    List<Grade> findByUserId(Long userId);
    List<Grade> findBySchoolId(Long schoolId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select g from Grade g where g.organizationId = :orgId "
            + "or (g.organizationId is null and g.userId = :userId)")
    List<Grade> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

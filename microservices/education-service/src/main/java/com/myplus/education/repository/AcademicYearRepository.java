package com.myplus.education.repository;

import com.myplus.education.entity.AcademicYear;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AcademicYearRepository extends JpaRepository<AcademicYear, Long> {

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select a from AcademicYear a where a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId) order by a.startDate desc")
    List<AcademicYear> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE year by a client-supplied id within the caller's tenant. */
    @Query("select a from AcademicYear a where a.id = :id and (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId))")
    Optional<AcademicYear> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                          @Param("userId") Long userId);
}

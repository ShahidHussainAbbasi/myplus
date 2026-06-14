package com.myplus.education.repository;

import com.myplus.education.entity.Attendance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    /** Upsert key: one attendance row per student per day within a tenant. */
    Optional<Attendance> findFirstByOrganizationIdAndEnAndAttDate(Long organizationId, String en, LocalDate attDate);

    /** All marks for a tenant on a given day (to pre-fill the roster). */
    List<Attendance> findByOrganizationIdAndAttDate(Long organizationId, LocalDate attDate);
    Page<Attendance> findByUserId(Long userId, Pageable pageable);
    List<Attendance> findByUserId(Long userId);
    Page<Attendance> findByUserIdAndEn(Long userId, String enrollNo, Pageable pageable);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select a from Attendance a where a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)")
    List<Attendance> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

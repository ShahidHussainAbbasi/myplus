package com.myplus.education.repository;

import com.myplus.education.entity.StaffAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StaffAttendanceRepository extends JpaRepository<StaffAttendance, Long> {

    /** The register for one day — the query the screen opens with. */
    @Query("select a from StaffAttendance a where (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)) "
            + "and a.attDate = :date order by a.staffName")
    List<StaffAttendance> findByDateScoped(@Param("date") LocalDate date,
                                           @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Existing rows for a set of staff on one day, in ONE query.
     *
     * A batch register upserts a whole staff list; asking per person would be the N+1 that finding D had to
     * undo elsewhere. Marked once per batch, exactly as 1.1 resolves the term once per batch.
     */
    @Query("select a from StaffAttendance a where (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)) "
            + "and a.attDate = :date and a.staffId in :staffIds")
    List<StaffAttendance> findByDateAndStaffScoped(@Param("date") LocalDate date,
                                                   @Param("staffIds") Collection<Long> staffIds,
                                                   @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** One person, one day — the UNIQUE key makes this at most one row. */
    @Query("select a from StaffAttendance a where (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)) "
            + "and a.staffId = :staffId and a.attDate = :date")
    Optional<StaffAttendance> findOneScoped(@Param("staffId") Long staffId, @Param("date") LocalDate date,
                                            @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE row by a client-supplied id within the caller's tenant. */
    @Query("select a from StaffAttendance a where a.id = :id and (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId))")
    Optional<StaffAttendance> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                             @Param("userId") Long userId);
}

package com.myplus.education.repository;

import com.myplus.education.entity.StaffAbsence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StaffAbsenceRepository extends JpaRepository<StaffAbsence, Long> {

    /** Who is out on one date — the query the whole screen opens with. */
    @Query("select a from StaffAbsence a where (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)) "
            + "and a.absenceDate = :date order by a.staffName")
    List<StaffAbsence> findByDateScoped(@Param("date") LocalDate date,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** The existing absence for one teacher on one date, if any (the UNIQUE key makes it at most one). */
    @Query("select a from StaffAbsence a where (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)) "
            + "and a.staffId = :staffId and a.absenceDate = :date")
    Optional<StaffAbsence> findOneScoped(@Param("staffId") Long staffId, @Param("date") LocalDate date,
                                         @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE absence by a client-supplied id within the caller's tenant. */
    @Query("select a from StaffAbsence a where a.id = :id and (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId))")
    Optional<StaffAbsence> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                          @Param("userId") Long userId);
}

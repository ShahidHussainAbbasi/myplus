package com.myplus.education.repository;

import com.myplus.education.entity.Substitution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubstitutionRepository extends JpaRepository<Substitution, Long> {

    /**
     * Everything recorded for one date — the morning list, and the input to the free-teacher exclusion.
     *
     * <p>Read ONCE per screen render and handed to {@link com.myplus.education.service.FreeTeacherFinder},
     * rather than queried per candidate. A day's substitutions are bounded by (absent teachers × periods),
     * which is small and does not grow with enrolment.
     */
    @Query("select s from Substitution s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) "
            + "and s.subDate = :date")
    List<Substitution> findByDateScoped(@Param("date") LocalDate date,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** The decision for one lesson on one day, if any (the UNIQUE key makes it at most one). */
    @Query("select s from Substitution s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) "
            + "and s.timetableEntryId = :entryId and s.subDate = :date")
    Optional<Substitution> findOneScoped(@Param("entryId") Long entryId, @Param("date") LocalDate date,
                                         @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** What one teacher is covering on a date — "am I covering anything?". */
    @Query("select s from Substitution s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) "
            + "and s.subDate = :date and s.coverStaffId = :staffId")
    List<Substitution> findByCoverScoped(@Param("staffId") Long staffId, @Param("date") LocalDate date,
                                         @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE substitution by a client-supplied id within the caller's tenant. */
    @Query("select s from Substitution s where s.id = :id and (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId))")
    Optional<Substitution> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                          @Param("userId") Long userId);
}

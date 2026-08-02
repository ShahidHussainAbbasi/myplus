package com.myplus.education.repository;

import com.myplus.education.entity.TimetableEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TimetableEntryRepository extends JpaRepository<TimetableEntry, Long> {

    /**
     * Every entry in a term — the grid, and the input to clash detection.
     *
     * <p>Clash detection reads this ONCE per save rather than running a query per rule. A term's timetable
     * is bounded by (classes × periods × days), which is small and does not grow with enrolment — unlike
     * attendance, this is a table it is legitimate to read whole.
     *
     * <p>{@code :termId is null} handles the term-less tenant (1.1) without a second method: JPQL cannot
     * compare NULL with {@code =}, so the null case is spelled out.
     */
    @Query("select t from TimetableEntry t where (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId)) "
            + "and ((:termId is null and t.termId is null) or t.termId = :termId) "
            + "order by t.dayOfWeek, t.periodId")
    List<TimetableEntry> findByTermScoped(@Param("termId") Long termId,
                                          @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** One class's week. */
    @Query("select t from TimetableEntry t where (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId)) "
            + "and ((:termId is null and t.termId is null) or t.termId = :termId) "
            + "and t.gradeId = :gradeId order by t.dayOfWeek, t.periodId")
    List<TimetableEntry> findByGradeScoped(@Param("gradeId") Long gradeId, @Param("termId") Long termId,
                                           @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** One teacher's week — "where am I today", and what 2.2 substitution will read. */
    @Query("select t from TimetableEntry t where (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId)) "
            + "and ((:termId is null and t.termId is null) or t.termId = :termId) "
            + "and t.staffId = :staffId order by t.dayOfWeek, t.periodId")
    List<TimetableEntry> findByStaffScoped(@Param("staffId") Long staffId, @Param("termId") Long termId,
                                           @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Does this term hold any entries? Copy-into-a-non-empty-term refuses on this (D-copy). */
    @Query("select count(t) from TimetableEntry t where (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId)) "
            + "and ((:termId is null and t.termId is null) or t.termId = :termId)")
    long countByTermScoped(@Param("termId") Long termId,
                           @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE entry by a client-supplied id within the caller's tenant. */
    @Query("select t from TimetableEntry t where t.id = :id and (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId))")
    Optional<TimetableEntry> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                            @Param("userId") Long userId);
}

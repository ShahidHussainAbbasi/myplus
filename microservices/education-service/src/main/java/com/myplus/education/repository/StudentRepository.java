package com.myplus.education.repository;

import com.myplus.education.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Page<Student> findByUserId(Long userId, Pageable pageable);
    List<Student> findByUserId(Long userId);

    /** Slice 0.2b: targeted update of the cached credit balance — never a full-entity save, which would clobber
     *  columns another request may have changed in the meantime. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Student s set s.creditBalance = :bal where s.id = :id")
    void updateCreditBalance(@org.springframework.data.repository.query.Param("id") Long id,
                             @org.springframework.data.repository.query.Param("bal") java.math.BigDecimal bal);

    /** Slice 0.2a: resolve one student by enrolment number within a tenant — an indexed lookup, so settling a fee
     *  payment costs one row read rather than scanning the org's students. */
    java.util.Optional<Student> findByOrganizationIdAndEnrollNo(Long organizationId, String enrollNo);
    Page<Student> findBySchoolId(Long schoolId, Pageable pageable);
    Page<Student> findByGradeId(Long gradeId, Pageable pageable);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select s from Student s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)")
    List<Student> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Party bridge: stamp ONLY party_id (targeted — never a full-entity save, which could clobber other columns). */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update student set party_id = :partyId where student_id = :id", nativeQuery = true)
    void updatePartyId(@Param("id") Long id, @Param("partyId") Long partyId);

    // P4 contact-view backfill: already-bridged rows, walked by an id cursor so the admin job can resume in batches.
    @Query("select s from Student s where s.partyId is not null and s.id > :afterId "
            + "and (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) order by s.id asc")
    List<Student> findBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId,
                                   @Param("userId") Long userId, Pageable pageable);

    @Query("select count(s) from Student s where s.partyId is not null and s.id > :afterId "
            + "and (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    long countBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId, @Param("userId") Long userId);

    // P4 — branch (school) scoped read, the education twin of business's findScopedByStores. Rows with no
    // school are legacy and stay visible (they drain as they are re-saved), exactly as with store_id.
    //
    // NOTE the deliberate difference from POS: a teacher sees their BRANCH's students, not merely the ones
    // they personally created. A roster is shared by the staff of a school — own-only (the cashier/till rule)
    // would hide a colleague's students from the teacher who has to teach them. Branch is the boundary here.
    @Query("select s from Student s where s.organizationId = :orgId "
            + "and (s.schoolId in :schoolIds or s.schoolId is null)")
    List<Student> findScopedBySchools(@Param("orgId") Long orgId, @Param("schoolIds") java.util.Collection<Long> schoolIds);

    /**
     * Slice 3.1 — the children of one guardian. <b>This query IS a parent's entire authority.</b>
     *
     * <p>Everything the parent portal returns is filtered to what this produces, derived fresh on every
     * request (design D1). Nothing is cached in a token or a column: a child transferring out, or a
     * guardian link being corrected, must take effect on the next request rather than at next login,
     * because a stale ACCESS list means a stranger reading a child's record.
     *
     * <p><b>No {@code userId} NULL-fallback</b>, unlike every staff read on this repository. That fallback
     * lets a staff member see rows they created before org-scoping landed; a parent has no such history,
     * and widening the predicate would widen an external principal's reach. Strict on purpose.
     */
    @Query("select s from Student s where s.organizationId = :orgId and s.guardianId = :guardianId "
            + "order by s.name")
    List<Student> findByGuardianScoped(@Param("guardianId") Long guardianId, @Param("orgId") Long orgId);

    // ── Finding D: dashboard aggregates ─────────────────────────────────────────────────────────
    // Counted in the database instead of loading every student to call .size() and .stream().filter().

    @Query("select count(s) from Student s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)")
    long countScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Enrolled this calendar year — the "fresh students" KPI. */
    @Query("select count(s) from Student s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) "
            + "and s.enrollDate >= :yearStart and s.enrollDate <= :yearEnd")
    long countEnrolledBetweenScoped(@Param("yearStart") LocalDate yearStart, @Param("yearEnd") LocalDate yearEnd,
                                    @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Active = status null or 'Active', preserving the old Java predicate exactly. */
    @Query("select count(s) from Student s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) "
            + "and (s.status is null or lower(s.status) = 'active')")
    long countActiveScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Enrolments per month within a bounded window — the 12-month trend, bounded in SQL not in Java. */
    @Query("select year(s.enrollDate), month(s.enrollDate), count(s) from Student s "
            + "where (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
            + "and s.enrollDate >= :from and s.enrollDate <= :to "
            + "group by year(s.enrollDate), month(s.enrollDate)")
    List<Object[]> countByEnrolMonthScoped(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                           @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("select s.gradeId, count(s) from Student s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId) group by s.gradeId")
    List<Object[]> countByGradeScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("select s.gender, count(s) from Student s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId) group by s.gender order by s.gender")
    List<Object[]> countByGenderScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("select s.status, count(s) from Student s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId) group by s.status order by s.status")
    List<Object[]> countByStatusScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Finding D — the duplicate check as an indexed EXISTS instead of loading every student.
     *
     * <p>Case-insensitivity comes from the column COLLATION (utf8mb4 …_ci), not from {@code lower()}:
     * wrapping the column in a function would be explicit and would also defeat the index, leaving a
     * query that looks careful and still scans the table. See slice doc D4 — this dependency is
     * load-bearing and is recorded in the V16 migration too.
     */
    @Query("select case when count(s) > 0 then true else false end from Student s "
            + "where (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
            + "and s.enrollNo = :enrollNo")
    boolean existsByEnrollNoScoped(@Param("enrollNo") String enrollNo,
                                   @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Enrolment numbers already duplicated in this tenant.
     *
     * <p>Not used by any screen. It exists so the UNIQUE constraint that would actually close the
     * check-then-act race can be applied later on data known to be clean — adding UNIQUE blind would
     * fail the migration on any tenant that already holds duplicates (DB standard D5).
     */
    @Query("select s.enrollNo from Student s where (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)) and s.enrollNo is not null "
            + "group by s.enrollNo having count(s) > 1")
    List<String> findDuplicateEnrollNosScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

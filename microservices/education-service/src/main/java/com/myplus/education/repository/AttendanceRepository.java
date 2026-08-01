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

    /**
     * Slice 1.5 — per-student present/total for a date range, aggregated IN THE DATABASE.
     * Returns {@code [enrollNo, present, total]} rows: ONE query for a whole class, never one per student.
     * Attendance is the biggest table in the service (a row per student per day), so pulling it into heap
     * to count is the mistake finding D is about — this is the shape the rest of that work should take.
     *
     * <p><b>Keyed on the DATE RANGE, not on {@code term_id}, deliberately.</b> 1.1 D5 added {@code term_id}
     * but never backfilled it ("don't infer history"), so keying a term summary on it would silently report
     * 0/0 for every term that predates 1.1 — a report card confidently stating a child attended nothing.
     * A term IS a date range, so the range is both correct and complete.
     *
     * <p>"Present" follows the convention already in {@code AnalyticsController.isPresent}: "present" or "p",
     * case-insensitively. Encoded once here and once there; unifying it belongs with the finding-D pass.
     */
    @Query("select a.en, "
            + "sum(case when lower(a.status) in ('present', 'p') then 1 else 0 end), "
            + "count(a) "
            + "from Attendance a where (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)) "
            + "and a.attDate >= :from and a.attDate <= :to "
            + "group by a.en")
    List<Object[]> summariseByStudent(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                      @Param("orgId") Long orgId, @Param("userId") Long userId);

    // ── Finding D: dashboard aggregates ─────────────────────────────────────────────────────────
    // Attendance is the biggest table in the service — one row per student per day, ~400k rows a year
    // for a 2,000-student school. The dashboard used to load ALL of it into heap and loop three times.
    // These three queries return a handful of rows each and never hydrate an entity.
    //
    // "Present" is defined ONCE, here, as lower(status) in ('present','p') — the same rule
    // summariseByStudent uses. It previously also existed as AnalyticsController.isPresent(); that
    // Java copy is gone, because two definitions of "was this child at school" is one too many.

    /** Whole-tenant present/total — the attendance-rate KPI, as two numbers instead of 400k rows. */
    @Query("select sum(case when lower(a.status) in ('present', 'p') then 1 else 0 end), count(a) "
            + "from Attendance a where a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)")
    // Declared List<Object[]> rather than Object[]: a single-row projection is ambiguous across
    // Hibernate versions (row vs row-wrapped-in-a-list). Taking element 0 is unambiguous everywhere.
    List<Object[]> summariseAllScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Present/total per DAY, newest first.
     *
     * <p>Ordered descending and sliced by the caller so the semantics of the old code survive exactly:
     * it showed "the last 30 days that actually have records", which is NOT the last 30 calendar days —
     * a school with no weekend records would otherwise show gaps it never used to show.
     */
    @Query("select a.attDate, "
            + "sum(case when lower(a.status) in ('present', 'p') then 1 else 0 end), count(a) "
            + "from Attendance a where (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)) and a.attDate is not null "
            + "group by a.attDate order by a.attDate desc")
    List<Object[]> summariseByDayScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Present/total per class, using the denormalised grade name the rows already carry. */
    @Query("select a.gn, "
            + "sum(case when lower(a.status) in ('present', 'p') then 1 else 0 end), count(a) "
            + "from Attendance a where a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId) "
            + "group by a.gn order by a.gn")
    List<Object[]> summariseByGradeNameScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

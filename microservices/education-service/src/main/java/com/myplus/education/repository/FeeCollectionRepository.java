package com.myplus.education.repository;

import com.myplus.education.entity.FeeCollection;
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
public interface FeeCollectionRepository extends JpaRepository<FeeCollection, Long> {
    Page<FeeCollection> findByUserId(Long userId, Pageable pageable);
    List<FeeCollection> findByUserId(Long userId);
    Page<FeeCollection> findByUserIdAndEnrollNo(Long userId, String enrollNo, Pageable pageable);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select f from FeeCollection f where f.organizationId = :orgId "
            + "or (f.organizationId is null and f.userId = :userId)")
    List<FeeCollection> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** A student's fee records within a tenant (ledger / previous balance / aging). */
    List<FeeCollection> findByOrganizationIdAndEnrollNoOrderByIdAsc(Long organizationId, String enrollNo);

    /**
     * Anti-IDOR: resolve ONE row by an id the client supplied, under the same tenant rule as
     * {@link #findScoped}. An edit that fetched by bare id then stamped organizationId would move
     * another tenant's row into the caller's org — silently taking it from its owner.
     */
    @Query("select f from FeeCollection f where f.id = :id and (f.organizationId = :orgId "
            + "or (f.organizationId is null and f.userId = :userId))")
    Optional<FeeCollection> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    // ── Finding D: dashboard aggregates ─────────────────────────────────────────────────────────
    // Every sum below used to be .stream().mapToLong().sum() over the whole fee table.
    //
    // NOTE the coalesce on every sum: SQL sum() over zero rows returns NULL, where the Java it replaces
    // returned 0. A brand-new tenant is exactly the case that would otherwise NPE or render "null" —
    // see the slice doc, test case 3.

    /** Collected and outstanding across the tenant — two numbers for the KPI row. */
    @Query("select coalesce(sum(f.feePaid), 0), coalesce(sum(f.dueBalance), 0) "
            + "from FeeCollection f where f.organizationId = :orgId "
            + "or (f.organizationId is null and f.userId = :userId)")
    // List<Object[]> not Object[] — a single-row projection is ambiguous across Hibernate versions.
    List<Object[]> sumTotalsScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Collected within a date range — the "this month" KPI. */
    @Query("select coalesce(sum(f.feePaid), 0) from FeeCollection f "
            + "where (f.organizationId = :orgId or (f.organizationId is null and f.userId = :userId)) "
            + "and f.paymentDate >= :from and f.paymentDate <= :to")
    long sumPaidBetweenScoped(@Param("from") LocalDate from, @Param("to") LocalDate to,
                              @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Collected + due per month over a bounded window — the 12-month fee trend. */
    @Query("select year(f.paymentDate), month(f.paymentDate), "
            + "coalesce(sum(f.feePaid), 0), coalesce(sum(f.dueAmount), 0) + coalesce(sum(f.otherDues), 0) "
            + "from FeeCollection f "
            + "where (f.organizationId = :orgId or (f.organizationId is null and f.userId = :userId)) "
            + "and f.paymentDate >= :from and f.paymentDate <= :to "
            + "group by year(f.paymentDate), month(f.paymentDate)")
    List<Object[]> sumByMonthScoped(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                    @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("select f.receivedIn, coalesce(sum(f.feePaid), 0) from FeeCollection f "
            + "where f.organizationId = :orgId or (f.organizationId is null and f.userId = :userId) "
            + "group by f.receivedIn order by f.receivedIn")
    List<Object[]> sumByReceivedInScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Collected per CLASS.
     *
     * <p>The old code built an enrolNo → gradeId map from the whole student table and then walked the
     * whole fee table against it. That join belongs in the database: fees are joined to students on
     * enrolment number within the same tenant, and grouped. A fee whose student cannot be resolved
     * groups under a null grade, which the caller labels "Unassigned" — the same outcome as before.
     */
    @Query("select s.gradeId, coalesce(sum(f.feePaid), 0) from FeeCollection f "
            + "left join Student s on s.enrollNo = f.enrollNo and s.organizationId = f.organizationId "
            + "where f.organizationId = :orgId or (f.organizationId is null and f.userId = :userId) "
            + "group by s.gradeId")
    List<Object[]> sumPaidByGradeScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

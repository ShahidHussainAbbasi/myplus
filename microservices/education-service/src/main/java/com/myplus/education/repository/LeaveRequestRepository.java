package com.myplus.education.repository;

import com.myplus.education.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    /**
     * Every request in a year — the input to the DERIVED balance (D1).
     *
     * Read ONCE per balance screen and handed to the pure LeaveBalanceCalculator, rather than a query per
     * staff member per type. Bounded by (staff x requests per year), which is small.
     */
    @Query("select r from LeaveRequest r where (r.organizationId = :orgId "
            + "or (r.organizationId is null and r.userId = :userId)) "
            + "and r.fromDate >= :yearStart and r.fromDate <= :yearEnd "
            + "order by r.fromDate desc")
    List<LeaveRequest> findByYearScoped(@Param("yearStart") LocalDate yearStart,
                                        @Param("yearEnd") LocalDate yearEnd,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** One teacher's requests in a year — their own balance, without loading the school's. */
    @Query("select r from LeaveRequest r where (r.organizationId = :orgId "
            + "or (r.organizationId is null and r.userId = :userId)) "
            + "and r.staffId = :staffId and r.fromDate >= :yearStart and r.fromDate <= :yearEnd "
            + "order by r.fromDate desc")
    List<LeaveRequest> findByStaffYearScoped(@Param("staffId") Long staffId,
                                             @Param("yearStart") LocalDate yearStart,
                                             @Param("yearEnd") LocalDate yearEnd,
                                             @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE request by a client-supplied id within the caller's tenant. */
    @Query("select r from LeaveRequest r where r.id = :id and (r.organizationId = :orgId "
            + "or (r.organizationId is null and r.userId = :userId))")
    Optional<LeaveRequest> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                          @Param("userId") Long userId);
}

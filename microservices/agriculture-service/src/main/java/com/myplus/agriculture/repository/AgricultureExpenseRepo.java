package com.myplus.agriculture.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.agriculture.entity.AgricultureExpense;

public interface AgricultureExpenseRepo extends JpaRepository<AgricultureExpense, Long>, QueryByExampleExecutor<AgricultureExpense> {

    // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
    @Query("select e from AgricultureExpense e where e.organizationId = :orgId "
         + "or (e.organizationId is null and e.userId = :userId)")
    List<AgricultureExpense> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** EX-9b — the farm's rows not yet imported into the books, oldest first (at most {@code page.size}). */
    @org.springframework.data.jpa.repository.Query("SELECT e FROM AgricultureExpense e WHERE e.organizationId = :org "
            + "AND e.expenseVoucherNo IS NULL ORDER BY e.dated ASC, e.id ASC")
    java.util.List<AgricultureExpense> notInBooks(@org.springframework.data.repository.query.Param("org") Long org,
                                                 org.springframework.data.domain.Pageable page);

    /** EX-9b — stamp an imported row: only this farm's, only once. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE AgricultureExpense e SET e.expenseVoucherNo = :no "
            + "WHERE e.id = :id AND e.organizationId = :org AND e.expenseVoucherNo IS NULL")
    int stampImported(@org.springframework.data.repository.query.Param("org") Long org,
                      @org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("no") String voucherNo);
}

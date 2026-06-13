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
}

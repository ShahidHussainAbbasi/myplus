package com.myplus.agriculture.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.agriculture.entity.AgricultureIncome;

public interface AgricultureIncomeRepo extends JpaRepository<AgricultureIncome, Long>, QueryByExampleExecutor<AgricultureIncome> {

    // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
    @Query("select i from AgricultureIncome i where i.organizationId = :orgId "
         + "or (i.organizationId is null and i.userId = :userId)")
    List<AgricultureIncome> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

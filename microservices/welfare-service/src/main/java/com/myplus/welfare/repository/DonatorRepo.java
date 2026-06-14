package com.myplus.welfare.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.welfare.entity.Donator;

public interface DonatorRepo extends JpaRepository<Donator, Long>, QueryByExampleExecutor<Donator> {

    // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
    @Query("select d from Donator d where d.organizationId = :orgId "
         + "or (d.organizationId is null and d.userId = :userId)")
    List<Donator> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

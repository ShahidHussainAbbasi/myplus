package com.myplus.agriculture.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.agriculture.entity.Land;

public interface LandRepo extends JpaRepository<Land, Long>, QueryByExampleExecutor<Land> {

    // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
    @Query("select l from Land l where l.organizationId = :orgId "
         + "or (l.organizationId is null and l.userId = :userId)")
    List<Land> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

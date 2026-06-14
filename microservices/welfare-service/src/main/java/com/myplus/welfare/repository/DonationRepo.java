package com.myplus.welfare.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.welfare.entity.Donation;

public interface DonationRepo extends JpaRepository<Donation, Long>, QueryByExampleExecutor<Donation> {

    // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
    @Query("select d from Donation d where d.organizationId = :orgId "
         + "or (d.organizationId is null and d.userId = :userId)")
    List<Donation> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

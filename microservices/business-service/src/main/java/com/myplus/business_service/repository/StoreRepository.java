package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Store registry (multi-location). Stores are org-scoped; access to each store is granted per user in auth. */
public interface StoreRepository extends JpaRepository<Store, Long> {

    @Query("select s from Store s where s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)")
    List<Store> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    List<Store> findByOrganizationId(Long organizationId);
}

package com.myplus.education.repository;

import com.myplus.education.entity.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    Page<Vehicle> findByUserId(Long userId, Pageable pageable);
    List<Vehicle> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select v from Vehicle v where v.organizationId = :orgId "
            + "or (v.organizationId is null and v.userId = :userId)")
    List<Vehicle> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

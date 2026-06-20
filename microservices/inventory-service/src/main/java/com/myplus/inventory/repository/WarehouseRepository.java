package com.myplus.inventory.repository;

import com.myplus.inventory.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped reads (slice 33, Phase 4.5). */
@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    String SCOPE = "(w.organizationId = :orgId OR (w.organizationId IS NULL AND w.userId = :userId))";

    @Query("SELECT w FROM Warehouse w WHERE " + SCOPE)
    List<Warehouse> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT w FROM Warehouse w WHERE w.id = :id AND " + SCOPE)
    Optional<Warehouse> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}

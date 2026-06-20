package com.myplus.inventory.repository;

import com.myplus.inventory.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped reads (slice 33, Phase 4.5). */
@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    String SCOPE = "(s.organizationId = :orgId OR (s.organizationId IS NULL AND s.userId = :userId))";

    @Query("SELECT s FROM Supplier s WHERE " + SCOPE)
    List<Supplier> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT s FROM Supplier s WHERE s.id = :id AND " + SCOPE)
    Optional<Supplier> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}

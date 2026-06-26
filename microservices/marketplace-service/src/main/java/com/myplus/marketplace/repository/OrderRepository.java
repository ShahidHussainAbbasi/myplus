package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped orders (E1, slice 46), NULL-fallback. */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    String SCOPE = "(o.organizationId = :orgId OR (o.organizationId IS NULL AND o.userId = :userId))";

    @Query("SELECT o FROM Order o WHERE " + SCOPE + " ORDER BY o.createdAt DESC")
    List<Order> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT o FROM Order o WHERE o.id = :id AND " + SCOPE)
    Optional<Order> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}

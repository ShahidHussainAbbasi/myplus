package com.myplus.inventory.repository;

import com.myplus.inventory.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped reads (slice 33, Phase 4.5). */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    String SCOPE = "(c.organizationId = :orgId OR (c.organizationId IS NULL AND c.userId = :userId))";

    @Query("SELECT c FROM Category c WHERE " + SCOPE)
    List<Category> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT c FROM Category c WHERE c.id = :id AND " + SCOPE)
    Optional<Category> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT c FROM Category c WHERE c.parentCategory IS NULL AND " + SCOPE)
    List<Category> findRootsScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT c FROM Category c WHERE c.parentCategory.id = :parentId AND " + SCOPE)
    List<Category> findByParentScoped(@Param("parentId") Long parentId, @Param("orgId") Long orgId, @Param("userId") Long userId);
}

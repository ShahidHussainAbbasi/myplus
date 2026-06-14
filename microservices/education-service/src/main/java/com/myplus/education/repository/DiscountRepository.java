package com.myplus.education.repository;

import com.myplus.education.entity.Discount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DiscountRepository extends JpaRepository<Discount, Long> {
    Page<Discount> findByUserId(Long userId, Pageable pageable);
    List<Discount> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select d from Discount d where d.organizationId = :orgId "
            + "or (d.organizationId is null and d.userId = :userId)")
    List<Discount> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

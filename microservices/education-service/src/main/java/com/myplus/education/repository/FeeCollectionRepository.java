package com.myplus.education.repository;

import com.myplus.education.entity.FeeCollection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeeCollectionRepository extends JpaRepository<FeeCollection, Long> {
    Page<FeeCollection> findByUserId(Long userId, Pageable pageable);
    List<FeeCollection> findByUserId(Long userId);
    Page<FeeCollection> findByUserIdAndEn(Long userId, String enrollNo, Pageable pageable);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select f from FeeCollection f where f.organizationId = :orgId "
            + "or (f.organizationId is null and f.userId = :userId)")
    List<FeeCollection> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** A student's fee records within a tenant (ledger / previous balance / aging). */
    List<FeeCollection> findByOrganizationIdAndEnOrderByIdAsc(Long organizationId, String en);
}

package com.myplus.pharma.repository;

import com.myplus.pharma.entity.MedicineClinical;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Per-item clinical flags (P7, slice 44), org-scoped NULL-fallback. */
@Repository
public interface MedicineClinicalRepository extends JpaRepository<MedicineClinical, Long> {

    String SCOPE = "(c.organizationId = :orgId OR (c.organizationId IS NULL AND c.userId = :userId))";

    @Query("SELECT c FROM MedicineClinical c WHERE " + SCOPE + " ORDER BY c.medicineName ASC")
    List<MedicineClinical> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT c FROM MedicineClinical c WHERE c.itemId = :itemId AND " + SCOPE)
    Optional<MedicineClinical> findByItemIdScoped(@Param("itemId") Long itemId,
                                                 @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT c FROM MedicineClinical c WHERE c.itemId IN :itemIds AND " + SCOPE)
    List<MedicineClinical> findByItemIdsScoped(@Param("itemIds") List<Long> itemIds,
                                              @Param("orgId") Long orgId, @Param("userId") Long userId);
}

package com.myplus.education.repository;

import com.myplus.education.entity.GradeBand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GradeBandRepository extends JpaRepository<GradeBand, Long> {

    /** The tenant's scale, lowest band first — the order every screen and the validator want. */
    @Query("select b from GradeBand b where b.organizationId = :orgId "
            + "or (b.organizationId is null and b.userId = :userId) order by b.minPercent")
    List<GradeBand> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE band by a client-supplied id within the caller's tenant. */
    @Query("select b from GradeBand b where b.id = :id and (b.organizationId = :orgId "
            + "or (b.organizationId is null and b.userId = :userId))")
    Optional<GradeBand> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                       @Param("userId") Long userId);
}

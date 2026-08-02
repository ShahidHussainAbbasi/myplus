package com.myplus.education.repository;

import com.myplus.education.entity.Period;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The school day in order — every screen wants it this way, so the ordering lives here. */
    @Query("select p from Period p where p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId) order by p.sequence, p.startTime")
    List<Period> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE period by a client-supplied id within the caller's tenant. */
    @Query("select p from Period p where p.id = :id and (p.organizationId = :orgId "
            + "or (p.organizationId is null and p.userId = :userId))")
    Optional<Period> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                    @Param("userId") Long userId);
}

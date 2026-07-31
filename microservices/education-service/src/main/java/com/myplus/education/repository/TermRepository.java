package com.myplus.education.repository;

import com.myplus.education.entity.Term;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TermRepository extends JpaRepository<Term, Long> {

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. */
    @Query("select t from Term t where t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId) order by t.startDate")
    List<Term> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** The terms of one year, scoped. */
    @Query("select t from Term t where t.academicYearId = :yearId and (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId)) order by t.sequence, t.startDate")
    List<Term> findByYearScoped(@Param("yearId") Long yearId, @Param("orgId") Long orgId,
                                @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE term by a client-supplied id within the caller's tenant. */
    @Query("select t from Term t where t.id = :id and (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId))")
    Optional<Term> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                  @Param("userId") Long userId);
}

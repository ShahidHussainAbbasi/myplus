package com.myplus.education.repository;

import com.myplus.education.entity.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    Page<Vehicle> findByUserId(Long userId, Pageable pageable);
    List<Vehicle> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select v from Vehicle v where v.organizationId = :orgId "
            + "or (v.organizationId is null and v.userId = :userId)")
    List<Vehicle> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    // P4 — branch (school) scoped read; school-less rows are legacy and stay visible.
    @Query("select v from Vehicle v where v.organizationId = :orgId "
            + "and (v.schoolId in :schoolIds or v.schoolId is null)")
    List<Vehicle> findScopedBySchools(@Param("orgId") Long orgId, @Param("schoolIds") java.util.Collection<Long> schoolIds);

    // ── Finding D: the duplicate check as an indexed EXISTS, not a full-table load ───────────────
    // Case-insensitivity comes from the column COLLATION (utf8mb4 …_ci), not from lower(): wrapping
    // the column in a function would also defeat the index (slice doc D4, recorded in V16).
    @Query("select case when count(v) > 0 then true else false end from Vehicle v "
            + "where (v.organizationId = :orgId or (v.organizationId is null and v.userId = :userId)) "
            + "and v.number = :number")
    boolean existsByNumberScoped(@Param("number") String number, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Values already duplicated in this tenant — enables the UNIQUE follow-up on clean data (D3). */
    @Query("select v.number from Vehicle v where (v.organizationId = :orgId "
            + "or (v.organizationId is null and v.userId = :userId)) and v.number is not null "
            + "group by v.number having count(v) > 1")
    List<String> findDuplicateNumbersScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

package com.myplus.education.repository;

import com.myplus.education.entity.Guardian;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuardianRepository extends JpaRepository<Guardian, Long> {
    Page<Guardian> findByUserId(Long userId, Pageable pageable);
    List<Guardian> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select g from Guardian g where g.organizationId = :orgId "
            + "or (g.organizationId is null and g.userId = :userId)")
    List<Guardian> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Anti-IDOR: resolve ONE row by an id the client supplied, under the same tenant rule as
     * {@link #findScoped}. An edit that fetched by bare id then stamped organizationId would move
     * another tenant's row into the caller's org — silently taking it from its owner.
     */
    @Query("select g from Guardian g where g.id = :id and (g.organizationId = :orgId "
            + "or (g.organizationId is null and g.userId = :userId))")
    Optional<Guardian> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Slice 3.3 D6 — does this address already belong to a GUARDIAN in this tenant?
     *
     * <p>Asked before a student is provisioned, and it prevents the worst silent failure in the slice:
     * {@code auth-service} keys a User by email and deliberately LINKS an existing address rather than
     * refusing (one adult may be a guardian at two schools), so a student record carrying their guardian's
     * address would resolve to the GUARDIAN'S login. auth-service cannot tell one person with two roles
     * from two different people — only education, holding both records, can.
     *
     * <p>Returns the first match: a tenant may legitimately hold two guardian rows with one family address,
     * and for this question any one of them is a refusal. Case-insensitivity comes from the column
     * collation (standard D3c).
     */
    @Query("select g from Guardian g where g.organizationId = :orgId and g.email = :email order by g.id")
    List<Guardian> findAllByEmailScoped(@Param("email") String email, @Param("orgId") Long orgId);

    // ── Finding D: the duplicate check as an indexed EXISTS, not a full-table load ───────────────
    // Composite name + CNIC: two guardians may share a name, so the identity document is what makes
    // it a duplicate. The old Java check required BOTH to be non-null and equal, which this reproduces
    // — a guardian with no CNIC recorded is therefore never treated as a duplicate, same as before.
    // Case-insensitivity comes from the column COLLATION (slice doc D4).
    @Query("select case when count(g) > 0 then true else false end from Guardian g "
            + "where (g.organizationId = :orgId or (g.organizationId is null and g.userId = :userId)) "
            + "and g.name = :name and g.cnic = :cnic")
    boolean existsByNameAndCnicScoped(@Param("name") String name, @Param("cnic") String cnic,
                                      @Param("orgId") Long orgId, @Param("userId") Long userId);
}

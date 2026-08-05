package com.myplus.party.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.party.entity.Party;

/** Tenant-scoped party reads (org + NULL-fallback) + the de-dup lookups (by contact, then email). */
@Repository
public interface PartyRepository extends JpaRepository<Party, Long> {

    String SCOPE = "(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))";

    @Query("SELECT p FROM Party p WHERE " + SCOPE + " ORDER BY p.name ASC")
    List<Party> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Party p WHERE p.id = :id AND " + SCOPE)
    Optional<Party> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Bulk backfill: the parties of this batch this tenant may touch — ONE query instead of a scoped read per row. */
    @Query("SELECT p FROM Party p WHERE p.id IN :ids AND " + SCOPE)
    List<Party> findAllByIdScoped(@Param("ids") List<Long> ids, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Phase 4a: a party's direct children (branches of a company, contacts of a branch). Tenant-scoped. */
    @Query("SELECT p FROM Party p WHERE p.parentPartyId = :parentId AND " + SCOPE + " ORDER BY p.name ASC")
    List<Party> findChildrenScoped(@Param("parentId") Long parentId,
                                   @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Phase 4a: every root of a hierarchy in this tenant — the top level of the account tree screen. */
    @Query("SELECT p FROM Party p WHERE p.parentPartyId IS NULL AND p.accountLevel <> 'INDIVIDUAL' AND " + SCOPE
         + " ORDER BY p.name ASC")
    List<Party> findAccountRootsScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Party p WHERE p.organizationId = :orgId AND p.contact = :contact")
    Optional<Party> findByOrgAndContact(@Param("orgId") Long orgId, @Param("contact") String contact);

    @Query("SELECT p FROM Party p WHERE p.organizationId = :orgId AND p.email = :email ORDER BY p.id ASC")
    List<Party> findByOrgAndEmail(@Param("orgId") Long orgId, @Param("email") String email);

    @Query("SELECT p FROM Party p WHERE " + SCOPE
         + " AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')) OR p.contact LIKE CONCAT('%', :q, '%')"
         + " OR LOWER(p.email) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY p.name ASC")
    List<Party> searchScoped(@Param("q") String q, @Param("orgId") Long orgId, @Param("userId") Long userId);
}

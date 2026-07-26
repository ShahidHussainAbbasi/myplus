package com.myplus.welfare.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.welfare.entity.Donator;

public interface DonatorRepo extends JpaRepository<Donator, Long>, QueryByExampleExecutor<Donator> {

    // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
    @Query("select d from Donator d where d.organizationId = :orgId "
         + "or (d.organizationId is null and d.userId = :userId)")
    List<Donator> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Party bridge: stamp ONLY party_id (targeted — never a full-entity save, which could clobber other columns). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update donator set party_id = :partyId where id = :id", nativeQuery = true)
    void updatePartyId(@Param("id") Long id, @Param("partyId") Long partyId);

    // P4 contact-view backfill: already-bridged rows, walked by an id cursor so the admin job can resume in batches.
    @Query("select d from Donator d where d.partyId is not null and d.id > :afterId "
         + "and (d.organizationId = :orgId or (d.organizationId is null and d.userId = :userId)) order by d.id asc")
    List<Donator> findBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId,
                                   @Param("userId") Long userId, org.springframework.data.domain.Pageable pageable);

    @Query("select count(d) from Donator d where d.partyId is not null and d.id > :afterId "
         + "and (d.organizationId = :orgId or (d.organizationId is null and d.userId = :userId))")
    long countBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId, @Param("userId") Long userId);
}

package com.myplus.business_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.PartySetOff;

/** DR-4 — set-off documents. Every read is tenant-scoped; an id from a request never reaches another tenant's row. */
public interface PartySetOffRepo extends JpaRepository<PartySetOff, Long> {

    @Query("select s from PartySetOff s where s.organizationId = :orgId and s.idempotencyKey = :key")
    Optional<PartySetOff> findByKey(@Param("orgId") Long orgId, @Param("key") String key);

    @Query("select s from PartySetOff s where s.organizationId = :orgId and s.reversalKey = :key")
    Optional<PartySetOff> findByReversalKey(@Param("orgId") Long orgId, @Param("key") String key);

    @Query("select s from PartySetOff s where s.id = :id and s.organizationId = :orgId")
    Optional<PartySetOff> findScoped(@Param("id") Long id, @Param("orgId") Long orgId);

    @Query("select s from PartySetOff s where s.organizationId = :orgId and s.partyId = :partyId order by s.id desc")
    List<PartySetOff> findByParty(@Param("orgId") Long orgId, @Param("partyId") Long partyId);

    /** DR-2 guard (design): link / unlink is refused while either record carries a set-off that still stands. */
    @Query("select count(s) from PartySetOff s where s.organizationId = :orgId and s.status = 'POSTED' "
         + "and (s.customerId = :customerId or s.venderId = :venderId)")
    long countPostedFor(@Param("orgId") Long orgId, @Param("customerId") Long customerId, @Param("venderId") Long venderId);
}

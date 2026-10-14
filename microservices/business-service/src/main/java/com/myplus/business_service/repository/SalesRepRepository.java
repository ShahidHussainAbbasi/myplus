package com.myplus.business_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.SalesRep;

/** SRA-2 — a business's sales reps. Every read is by organisation: an id from a request is never looked up unscoped. */
public interface SalesRepRepository extends JpaRepository<SalesRep, Long> {

    @Query("select r from SalesRep r where r.organizationId = :orgId order by r.name")
    List<SalesRep> findByOrg(@Param("orgId") Long orgId);

    @Query("select r from SalesRep r where r.id = :id and r.organizationId = :orgId")
    Optional<SalesRep> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId);

    /** The rep a login IS, in this business — how a signed-in rep is recognised. */
    @Query("select r from SalesRep r where r.organizationId = :orgId and r.userId = :userId")
    Optional<SalesRep> findByUser(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Case-insensitive by the column's collation, the same rule the UNIQUE key enforces. */
    @Query("select r from SalesRep r where r.organizationId = :orgId and r.name = :name")
    Optional<SalesRep> findByName(@Param("orgId") Long orgId, @Param("name") String name);
}

package com.myplus.business_service.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.Area;

/** SRA-1 — a business's areas. Every read is by organisation: an id from a request is never looked up unscoped. */
public interface AreaRepository extends JpaRepository<Area, Long> {

    @Query("select a from Area a where a.organizationId = :orgId order by a.name")
    List<Area> findByOrg(@Param("orgId") Long orgId);

    @Query("select a from Area a where a.id = :id and a.organizationId = :orgId")
    Optional<Area> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId);

    @Query("select a from Area a where a.id in :ids and a.organizationId = :orgId")
    List<Area> findAllScoped(@Param("ids") Collection<Long> ids, @Param("orgId") Long orgId);

    /** Case-insensitive by the column's collation, the same rule the UNIQUE key enforces. */
    @Query("select a from Area a where a.organizationId = :orgId and a.cityId = :cityId and a.name = :name")
    Optional<Area> findByName(@Param("orgId") Long orgId, @Param("cityId") Long cityId, @Param("name") String name);
}

package com.myplus.business_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.City;

/** SRA-1 — a business's cities. Every read is by organisation: an id from a request is never looked up unscoped. */
public interface CityRepository extends JpaRepository<City, Long> {

    @Query("select c from City c where c.organizationId = :orgId order by c.name")
    List<City> findByOrg(@Param("orgId") Long orgId);

    @Query("select c from City c where c.id = :id and c.organizationId = :orgId")
    Optional<City> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId);

    /** Case-insensitive by the column's collation, the same rule the UNIQUE key enforces. */
    @Query("select c from City c where c.organizationId = :orgId and c.name = :name")
    Optional<City> findByName(@Param("orgId") Long orgId, @Param("name") String name);
}

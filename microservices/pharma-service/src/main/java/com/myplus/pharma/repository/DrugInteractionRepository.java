package com.myplus.pharma.repository;

import com.myplus.pharma.entity.DrugInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Drug interactions (P7, slice 44), org-scoped. */
@Repository
public interface DrugInteractionRepository extends JpaRepository<DrugInteraction, Long> {

    String SCOPE = "(d.organizationId = :orgId OR (d.organizationId IS NULL AND d.userId = :userId))";

    /** Interactions where BOTH items are in the dispensed set. */
    @Query("SELECT d FROM DrugInteraction d WHERE d.itemId1 IN :itemIds AND d.itemId2 IN :itemIds AND " + SCOPE)
    List<DrugInteraction> findAmongScoped(@Param("itemIds") List<Long> itemIds,
                                          @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Existing interaction for a pair (either order), scoped — so addInteraction upserts instead of duplicating. */
    @Query("SELECT d FROM DrugInteraction d WHERE ((d.itemId1 = :a AND d.itemId2 = :b) OR (d.itemId1 = :b AND d.itemId2 = :a)) AND " + SCOPE)
    java.util.Optional<DrugInteraction> findPairScoped(@Param("a") Long a, @Param("b") Long b,
                                                       @Param("orgId") Long orgId, @Param("userId") Long userId);
}

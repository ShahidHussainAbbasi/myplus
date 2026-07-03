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
    @Query("SELECT d FROM DrugInteraction d WHERE d.productId1 IN :productIds AND d.productId2 IN :productIds AND " + SCOPE)
    List<DrugInteraction> findAmongScoped(@Param("productIds") List<Long> productIds,
                                          @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Existing interaction for a pair (either order), scoped — so addInteraction upserts instead of duplicating. */
    @Query("SELECT d FROM DrugInteraction d WHERE ((d.productId1 = :a AND d.productId2 = :b) OR (d.productId1 = :b AND d.productId2 = :a)) AND " + SCOPE)
    java.util.Optional<DrugInteraction> findPairScoped(@Param("a") Long a, @Param("b") Long b,
                                                       @Param("orgId") Long orgId, @Param("userId") Long userId);
}

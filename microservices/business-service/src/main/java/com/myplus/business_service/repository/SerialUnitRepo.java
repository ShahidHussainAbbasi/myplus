package com.myplus.business_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.SerialUnit;

/**
 * SER-2 — the per-unit register. <b>Every query is tenant-scoped;</b> there is no unscoped read, because a
 * serial is exactly the kind of identifier somebody would try to look up across tenants.
 */
public interface SerialUnitRepo extends JpaRepository<SerialUnit, Long> {

    /**
     * The live unit carrying this serial, if any.
     *
     * <p>Keyed on {@code status} rather than on {@code liveSerialNo}: the generated column exists to make the
     * DATABASE enforce uniqueness, and querying it here would tie the read to that implementation detail. The
     * status filter says what is meant — "the one that is in stock".
     */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.serialNo = :serialNo "
         + "AND s.status = 'IN_STOCK'")
    Optional<SerialUnit> findLive(@Param("orgId") Long orgId, @Param("serialNo") String serialNo);

    /** Everything ever recorded under this serial — the history a warranty or police enquiry actually needs. */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.serialNo = :serialNo "
         + "ORDER BY s.serialUnitId DESC")
    List<SerialUnit> findHistory(@Param("orgId") Long orgId, @Param("serialNo") String serialNo);

    /** Units of a product currently in stock — what a cashier picks from when selling a tracked item. */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.productId = :productId "
         + "AND s.status = 'IN_STOCK' ORDER BY s.serialUnitId")
    List<SerialUnit> findInStock(@Param("orgId") Long orgId, @Param("productId") Long productId);

    /** How many units of this product are on the shelf. A COUNT, not a list-and-size. */
    @Query("SELECT COUNT(s) FROM SerialUnit s WHERE s.organizationId = :orgId AND s.productId = :productId "
         + "AND s.status = 'IN_STOCK'")
    long countInStock(@Param("orgId") Long orgId, @Param("productId") Long productId);

    /** The units a given purchase brought in — used when a purchase is edited or reversed. */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.purchaseId = :purchaseId")
    List<SerialUnit> findByPurchase(@Param("orgId") Long orgId, @Param("purchaseId") Long purchaseId);
}

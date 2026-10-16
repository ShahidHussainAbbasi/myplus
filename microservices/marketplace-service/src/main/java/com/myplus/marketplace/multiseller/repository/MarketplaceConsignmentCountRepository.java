package com.myplus.marketplace.multiseller.repository;

import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentCount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** MKT-5d — the warehouse's counts of consigned stock (V43). */
public interface MarketplaceConsignmentCountRepository extends JpaRepository<MarketplaceConsignmentCount, Long> {

    /** A count repeated with the same key replays it instead of counting twice. */
    Optional<MarketplaceConsignmentCount> findByConsigneeOrgIdAndIdempotencyKey(Long consigneeOrgId, String idempotencyKey);

    /** Units counts found missing and not yet resolved: still in the owner's books, never sold. */
    @Query("SELECT COALESCE(SUM(c.missing - c.writtenOff - c.approved), 0) FROM MarketplaceConsignmentCount c "
            + "WHERE c.consignorOrgId = :consignor AND c.consigneeOrgId = :consignee AND c.mktProductId = :product")
    long openMissing(@Param("consignor") Long consignor, @Param("consignee") Long consignee, @Param("product") Long product);

    /** One owner's product's counts, newest first: the held row's history. */
    List<MarketplaceConsignmentCount> findTop10ByConsignorOrgIdAndConsigneeOrgIdAndMktProductIdOrderByIdDesc(
            Long consignor, Long consignee, Long product);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MarketplaceConsignmentCount c WHERE c.id = :id")
    Optional<MarketplaceConsignmentCount> lockById(@Param("id") Long id);
}

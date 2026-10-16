package com.myplus.marketplace.multiseller.repository;

import com.myplus.marketplace.multiseller.entity.MarketplaceShrinkageClaim;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** MKT-5d — shrinkage claims (V43). */
public interface MarketplaceShrinkageClaimRepository extends JpaRepository<MarketplaceShrinkageClaim, Long> {

    List<MarketplaceShrinkageClaim> findByCountIdInOrderByIdDesc(Collection<Long> countIds);

    /** Units of a count under claims still waiting for the operator. */
    @Query("SELECT COALESCE(SUM(c.units), 0) FROM MarketplaceShrinkageClaim c WHERE c.countId = :count AND c.status = 'OPEN'")
    long openUnits(@Param("count") Long countId);

    /** Units of a count already claimed and rejected: the dispute is over, they can only be written off. */
    @Query("SELECT COALESCE(SUM(c.units), 0) FROM MarketplaceShrinkageClaim c WHERE c.countId = :count AND c.status = 'REJECTED'")
    long rejectedUnits(@Param("count") Long countId);

    /** The operator's queue: OPEN first (oldest first), then the latest decided. */
    @Query("SELECT c FROM MarketplaceShrinkageClaim c ORDER BY CASE WHEN c.status = 'OPEN' THEN 0 ELSE 1 END, "
            + "CASE WHEN c.status = 'OPEN' THEN c.id ELSE -c.id END")
    List<MarketplaceShrinkageClaim> queue(Pageable page);
}

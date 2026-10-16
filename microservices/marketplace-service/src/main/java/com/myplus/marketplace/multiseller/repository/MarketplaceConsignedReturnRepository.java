package com.myplus.marketplace.multiseller.repository;

import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedReturn;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** MKT-5e — consigned stock going back to its owner (V44). */
public interface MarketplaceConsignedReturnRepository extends JpaRepository<MarketplaceConsignedReturn, Long> {

    /** A request repeated with the same key replays the first instead of asking twice. */
    Optional<MarketplaceConsignedReturn> findByRequestedByOrgIdAndIdempotencyKey(Long requestedByOrgId, String idempotencyKey);

    /** Units of one owner's product in returns in one of {@code statuses}. */
    @Query("SELECT COALESCE(SUM(r.units), 0) FROM MarketplaceConsignedReturn r WHERE r.consignorOrgId = :consignor "
            + "AND r.consigneeOrgId = :consignee AND r.mktProductId = :product AND r.status IN :statuses")
    long units(@Param("consignor") Long consignor, @Param("consignee") Long consignee, @Param("product") Long product,
               @Param("statuses") Collection<String> statuses);

    /** One owner's product's returns, newest first: the held row's history. */
    List<MarketplaceConsignedReturn> findTop10ByConsignorOrgIdAndConsigneeOrgIdAndMktProductIdOrderByIdDesc(
            Long consignor, Long consignee, Long product);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM MarketplaceConsignedReturn r WHERE r.id = :id")
    Optional<MarketplaceConsignedReturn> lockById(@Param("id") Long id);
}

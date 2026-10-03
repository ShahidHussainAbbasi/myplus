package com.myplus.marketplace.multiseller.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;

public interface MarketplaceOfferProjectionRepository extends JpaRepository<MarketplaceOfferProjection, Long> {

    /** The public read — idx_mkt_proj_product_status_price. Bounded by the number of sellers of one product. */
    List<MarketplaceOfferProjection> findByMktProductIdAndStatusOrderByPriceAsc(Long mktProductId, String status);

    /** The stock refresher — idx_mkt_proj_status_sync: LIVE rows confirmed longest ago (NULL = never) first. */
    List<MarketplaceOfferProjection> findByStatusOrderByLastSyncAtAsc(String status, Pageable page);

    List<MarketplaceOfferProjection> findByStatusAndLastSyncAtBefore(String status, LocalDateTime before, Pageable page);
}

package com.myplus.marketplace.multiseller.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;

public interface MarketplaceOrderLineRepository extends JpaRepository<MarketplaceOrderLine, Long> {

    /** idx_mkt_line_seller_order. */
    List<MarketplaceOrderLine> findBySellerOrderIdOrderByIdAsc(Long sellerOrderId);

    /** One query for a page of seller orders (no N+1). */
    List<MarketplaceOrderLine> findBySellerOrderIdIn(Collection<Long> sellerOrderIds);
}

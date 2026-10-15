package com.myplus.marketplace.multiseller.repository;

import com.myplus.marketplace.multiseller.entity.MarketplaceStockTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** MKT-3c — stock transfers to the warehouse. Every read is scoped to one side's organisation. */
public interface MarketplaceStockTransferRepository extends JpaRepository<MarketplaceStockTransfer, Long> {

    /** The seller's own transfers, newest first. */
    Page<MarketplaceStockTransfer> findBySellerOrgIdOrderByIdDesc(Long sellerOrgId, Pageable page);

    /** What has been sent to this warehouse, newest first. */
    Page<MarketplaceStockTransfer> findByWarehouseOrgIdOrderByIdDesc(Long warehouseOrgId, Pageable page);
}

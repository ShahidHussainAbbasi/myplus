package com.myplus.marketplace.multiseller.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;

public interface MarketplaceOrderRepository extends JpaRepository<MarketplaceOrder, Long> {

    /** Checkout replay — uk_mkt_order_idem. */
    Optional<MarketplaceOrder> findByIdempotencyKey(String idempotencyKey);

    /** Tracking — uk_mkt_order_no. */
    Optional<MarketplaceOrder> findByOrderNo(String orderNo);

    /** Operator list — idx_mkt_order_status_created. */
    Page<MarketplaceOrder> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

    Page<MarketplaceOrder> findAllByOrderByCreatedAtDesc(Pageable page);

    /** The abuse guard — idx_mkt_order_phone_status. Phones are stored as digits only. */
    long countByCustomerPhoneAndStatus(String customerPhone, String status);
}

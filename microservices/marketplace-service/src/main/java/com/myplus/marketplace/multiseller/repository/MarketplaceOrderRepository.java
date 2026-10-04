package com.myplus.marketplace.multiseller.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;

public interface MarketplaceOrderRepository extends JpaRepository<MarketplaceOrder, Long> {

    /** Checkout replay — uk_mkt_order_idem. */
    Optional<MarketplaceOrder> findByIdempotencyKey(String idempotencyKey);

    /**
     * MKT-2a — the order row locked for the rest of the transaction. Every refund of a multi-seller order (one part's,
     * or the remainder when the whole order ends) is decided under this lock, so two parts ending together can never
     * both count the same money.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from MarketplaceOrder o where o.id = :id")
    Optional<MarketplaceOrder> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    /** Tracking — uk_mkt_order_no. */
    Optional<MarketplaceOrder> findByOrderNo(String orderNo);

    /** Operator list — idx_mkt_order_status_created. */
    Page<MarketplaceOrder> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

    Page<MarketplaceOrder> findAllByOrderByCreatedAtDesc(Pageable page);

    /** The abuse guard — idx_mkt_order_phone_status. Phones are stored as digits only. */
    long countByCustomerPhoneAndStatus(String customerPhone, String status);

    /** MKT-1e2 "My orders" — idx_mkt_order_customer_created. Only orders proven to be the account's. */
    Page<MarketplaceOrder> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable page);
}

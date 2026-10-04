package com.myplus.marketplace.multiseller.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplacePayment;

public interface MarketplacePaymentRepository extends JpaRepository<MarketplacePayment, Long> {

    /** uk_mkt_payment_idem — one fact per key: a retried charge or refund finds the first one. */
    Optional<MarketplacePayment> findByIdempotencyKey(String idempotencyKey);

    /** An order's payment history, oldest first (My orders shows it). */
    List<MarketplacePayment> findByMktOrderIdOrderByIdAsc(Long mktOrderId);

    /** Charges whose answer never came: the sweeper asks again with the SAME key. idx_mkt_payment_status. */
    List<MarketplacePayment> findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(String status, LocalDateTime before);
}

package com.myplus.marketplace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.myplus.marketplace.entity.OrderAmendment;

/** OMS O7 D1 — the amendment trail for an order, oldest first (V18's {@code idx_order_amendment_order}). */
@Repository
public interface OrderAmendmentRepository extends JpaRepository<OrderAmendment, Long> {

    /**
     * Bounded by the order, so this is not an OMS-7 unbounded read: one order's amendments are a handful of
     * rows by construction, and the caller already holds the order.
     */
    List<OrderAmendment> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}

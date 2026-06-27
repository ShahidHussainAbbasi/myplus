package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Order fulfilment timeline (slice 57). */
public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}

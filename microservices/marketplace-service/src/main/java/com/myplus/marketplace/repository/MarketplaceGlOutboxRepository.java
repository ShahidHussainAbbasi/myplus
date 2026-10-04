package com.myplus.marketplace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.entity.MarketplaceGlOutbox;

/** MKT-1g — the operator's journals waiting for finance (the shared OutboxRelay drives it). */
public interface MarketplaceGlOutboxRepository extends JpaRepository<MarketplaceGlOutbox, Long> {

    List<MarketplaceGlOutbox> findTop100ByStatusOrderByIdAsc(String status);
}

package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.DrawerExpenseOutbox;

/** EX-3 — the till pay-out work queue (driven by the shared OutboxRelay). */
public interface DrawerExpenseOutboxRepo extends JpaRepository<DrawerExpenseOutbox, Long> {

    List<DrawerExpenseOutbox> findTop100ByStatusOrderByIdAsc(String status);
}

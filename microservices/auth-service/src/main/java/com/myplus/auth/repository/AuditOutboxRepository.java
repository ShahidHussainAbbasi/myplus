package com.myplus.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.auth.entity.AuditOutbox;

/** E4 — outbox store for reliable delivery of control-plane events to audit-service (by id + pending scan). */
public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, Long> {

    List<AuditOutbox> findTop100ByStatusOrderByIdAsc(String status);
}

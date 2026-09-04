package com.myplus.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.catalog.entity.AuditOutbox;

/** E5 — outbox store for reliable delivery of catalog audit events to audit-service. */
public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, Long> {

    List<AuditOutbox> findTop100ByStatusOrderByIdAsc(String status);
}

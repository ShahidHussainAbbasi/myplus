package com.myplus.marketplace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.entity.AuditOutbox;

/** MKT-1f (G-16) — the audit outbox; {@code idx_audit_outbox_pending} serves the re-drive. */
public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, Long> {

    List<AuditOutbox> findTop100ByStatusOrderByIdAsc(String status);
}

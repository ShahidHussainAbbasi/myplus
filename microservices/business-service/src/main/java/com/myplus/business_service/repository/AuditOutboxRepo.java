package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.AuditOutbox;

/** Audit #6: outbox store for reliable delivery to audit-service (pending scan + by-id). */
public interface AuditOutboxRepo extends JpaRepository<AuditOutbox, Long> {

    List<AuditOutbox> findTop100ByStatusOrderByIdAsc(String status);
}

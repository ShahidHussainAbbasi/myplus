package com.myplus.audit.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.audit.dto.AuditRecordRequest;
import com.myplus.audit.entity.AuditEvent;
import com.myplus.audit.repository.AuditEventRepository;
import com.myplus.common.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * Ingests + serves audit events. Identity (org + actor userId) comes from the authenticated request (the producer
 * impersonates the tenant via the gateway), never from the payload — so entries can't be spoofed. Ingestion is
 * idempotent on (org, eventKey): a retried delivery is a no-op. Append-only — no update/delete.
 */
@Service
@RequiredArgsConstructor
public class AuditIngestService {

    private final AuditEventRepository repo;

    @Transactional
    public void record(AuditRecordRequest req) {
        if (req == null || req.getAction() == null) return;
        Long org = CurrentUser.organizationId();
        String key = req.getEventKey();
        // Idempotent: skip a duplicate delivery. A concurrent race hits the unique index → this tx rolls back → the
        // producer's outbox retries → the retry finds the row here and skips.
        if (key != null && !key.isBlank() && repo.existsByOrganizationIdAndEventKey(org, key)) return;
        repo.save(AuditEvent.builder()
                .organizationId(org)
                .userId(CurrentUser.userId())
                .sourceService(req.getSourceService())
                .action(req.getAction())
                .entityType(req.getEntityType())
                .entityRef(req.getEntityRef())
                .amount(req.getAmount())
                .details(req.getDetails() != null && req.getDetails().length() > 500
                        ? req.getDetails().substring(0, 500) : req.getDetails())
                .eventKey(key)
                .occurredAt(req.getOccurredAt())
                .receivedAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> list(String action, int limit) {
        Long org = CurrentUser.organizationId();
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        return (action == null || action.isBlank())
                ? repo.findByOrg(org, page)
                : repo.findByOrgAndAction(org, action, page);
    }
}

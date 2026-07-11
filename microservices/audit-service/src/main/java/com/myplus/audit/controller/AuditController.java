package com.myplus.audit.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.myplus.audit.dto.AuditRecordRequest;
import com.myplus.audit.entity.AuditEvent;
import com.myplus.audit.service.AuditIngestService;

import lombok.RequiredArgsConstructor;

/**
 * The audit trail API. Mapped at the full {@code /api/audit/...} path (the gateway routes {@code /api/audit/**} here
 * with no StripPrefix). Producers POST events; operators/dashboards read them (org-scoped).
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditIngestService service;

    /** Ingest one audit event (idempotent on eventKey). Called by producers via AuditClient. */
    @PostMapping("/record")
    public Map<String, Object> record(@RequestBody AuditRecordRequest req) {
        service.record(req);
        return Map.of("status", "OK");
    }

    /** Read the tenant's audit trail (newest first), optionally filtered by action. */
    @GetMapping
    public List<AuditEvent> list(@RequestParam(required = false) String action,
                                 @RequestParam(required = false, defaultValue = "200") int limit) {
        return service.list(action, limit);
    }
}

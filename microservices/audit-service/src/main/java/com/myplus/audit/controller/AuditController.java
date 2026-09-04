package com.myplus.audit.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
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

    /**
     * Read a tenant's audit trail (newest first), optionally filtered by action.
     *
     * <h3>E4 finding A3 — this endpoint used to require only that you were logged in</h3>
     * Which meant a cashier could fetch every {@code RECEIPT} and {@code PAYMENT} in the organization, with
     * amounts. E4 was about to add "the platform suspended you for non-payment, reason: …" to the same list,
     * so the gate belongs here rather than in a later slice.
     *
     * <h3>ROLE_OWNER / ROLE_ADMIN, never ADMIN_PRIVILEGE</h3>
     * Every tenant owner holds the super privilege set inside their own organization, so a privilege gate
     * would be no gate at all — the reasoning E1 and E2 both already record for the entitlement endpoints.
     * {@code ROLE_ADMIN} is the platform operator; {@code ROLE_OWNER} is the tenant's own owner.
     *
     * <p>{@code organizationId} is honoured only for {@code ROLE_ADMIN} — see
     * {@link com.myplus.audit.service.AuditIngestService#list}. A refusal here is a real 403: a security
     * event, reported as one.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ROLE_ADMIN')")
    public List<AuditEvent> list(@RequestParam(required = false) String action,
                                 @RequestParam(required = false, defaultValue = "200") int limit,
                                 @RequestParam(required = false) Long organizationId) {
        return service.list(action, limit, organizationId);
    }
}

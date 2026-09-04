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
                /*
                 * E4 — the actor axis. Producer-asserted (see AuditRecordRequest's trust note), and defaulted
                 * to the safe reading rather than left null: an event with no actor information is
                 * indistinguishable from a legacy row, and "we do not know" must not be silently rendered as
                 * "one of your own staff". A producer that says nothing is treated as an insider, which is
                 * what every pre-E4 producer is.
                 */
                .actorOrgId(req.getActorOrgId() != null ? req.getActorOrgId() : org)
                .actorType(req.getActorType() != null && !req.getActorType().isBlank()
                        ? req.getActorType() : "MEMBER")
                .actorEmail(req.getActorEmail() != null ? req.getActorEmail() : CurrentUser.email())
                .reason(trim(req.getReason(), 255))
                .beforeValue(trim(req.getBeforeValue(), 64))
                .afterValue(trim(req.getAfterValue(), 64))
                .sourceService(req.getSourceService())
                .action(req.getAction())
                .entityType(req.getEntityType())
                .entityRef(req.getEntityRef())
                .amount(req.getAmount())
                .details(trim(req.getDetails(), 500))
                .eventKey(key)
                .occurredAt(req.getOccurredAt())
                .receivedAt(LocalDateTime.now())
                .build());
    }

    /** Longest value that fits the column, so a long free-text field cannot fail the whole insert. */
    private static String trim(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
    }

    /**
     * One tenant's trail, newest first.
     *
     * <h3>The organizationId parameter is OPERATOR-ONLY (the ONB-3 rule)</h3>
     * A platform operator has to be able to read the trail of the tenant they are looking at — that is the
     * whole of E4's console panel. {@link CurrentUser#organizationIdFor} honours the parameter for
     * {@code ROLE_ADMIN} and silently gives everyone else their own org: IGNORED rather than rejected, so a
     * caller probing ids learns nothing from the difference between a refusal and an empty answer.
     *
     * <p>Without this, the {@code @PreAuthorize} on the controller would be worthless — an owner permitted to
     * read "their own" trail could simply ask for somebody else's.
     */
    @Transactional(readOnly = true)
    public List<AuditEvent> list(String action, int limit, Long requestedOrganizationId) {
        Long org = CurrentUser.organizationIdFor(requestedOrganizationId);
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        return (action == null || action.isBlank())
                ? repo.findByOrg(org, page)
                : repo.findByOrgAndAction(org, action, page);
    }
}

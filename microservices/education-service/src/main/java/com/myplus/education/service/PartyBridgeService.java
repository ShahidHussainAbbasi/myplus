package com.myplus.education.service;

import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.commerce.contracts.dto.PartyRoleRef;
import com.myplus.common.security.CurrentUser;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Party bridge (P3): links a Student to the shared party master (party-service). Decoupled: the write only PUBLISHES a
 * request; the party upsert + stamp run in an {@code AFTER_COMMIT} handler ({@code REQUIRES_NEW} tx), so the domain
 * transaction/connection is released BEFORE the party HTTP call (a slow/down party-service can't hold a DB connection
 * or roll the student write back). BEST-EFFORT: a hiccup is logged and re-attempted on the next write; the guard skips
 * once {@code party_id} is set. The handler runs on the request thread, so the caller's org is still forwarded to
 * party-service (no runAs needed).
 */
@Service
public class PartyBridgeService {

    private static final Logger LOG = LoggerFactory.getLogger(PartyBridgeService.class);

    // Lightweight circuit breaker (see business PartyBridgeService): skip party calls for a cooldown after a run of
    // failures, so a sustained party outage doesn't make every new-student write pay the full client timeout.
    private static final int CB_THRESHOLD = 5;
    private static final long CB_COOLDOWN_MS = 30_000L;
    // Backfill batch cap: it reuses the hot-path party client (2s read timeout), so a batch must finish well inside
    // that. Call again with the returned cursor to continue — the link write is idempotent.
    private static final int MAX_BATCH = 200;
    private final java.util.concurrent.atomic.AtomicInteger cbFailures = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long cbOpenUntil = 0L;

    /** Identity captured at publish time so the after-commit handler needs no entity reload. */
    public record PartyBridgeRequest(Long id, String name, String contact, String email, String address) {}

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    private PartyClient partyClient;   // null if party-service is unwired in this deployment

    @Autowired
    private StudentRepository studentRepository;

    /** Queue a student for bridging (best-effort, once). No-op if already bridged. Runs after the caller's tx commits. */
    public void bridgeStudent(Student s) {
        if (s == null || s.getId() == null || s.getPartyId() != null) return;
        events.publishEvent(new PartyBridgeRequest(s.getId(), s.getName(), s.getMobile(), s.getEmail(), s.getAddress()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBridge(PartyBridgeRequest req) {
        if (partyClient == null || System.currentTimeMillis() < cbOpenUntil) return;   // unwired or circuit open
        try {
            PartyRef ref = partyClient.upsert(PartyRef.builder()
                    .partyType("STUDENT").name(req.name()).contact(req.contact())
                    .email(req.email()).address(req.address())
                    .role(PartyRoleRef.builder()   // P4: identity AND role link in one call
                            .module("education").role("STUDENT").localId(req.id()).label(req.name()).build())
                    .build());
            cbFailures.set(0);   // a successful call closes the breaker
            if (ref != null && ref.getId() != null) studentRepository.updatePartyId(req.id(), ref.getId());
        } catch (Exception e) {
            if (cbFailures.incrementAndGet() >= CB_THRESHOLD) { cbOpenUntil = System.currentTimeMillis() + CB_COOLDOWN_MS; cbFailures.set(0); }
            LOG.warn("party bridge (student {}) failed after commit — will retry on next write", req.id(), e);
        }
    }

    /**
     * One-time backfill for students bridged BEFORE P4: they already carry a party_id, so the skip-guard means they
     * never bridge again and would have no role link. Batched by id cursor, idempotent — re-run until remaining is 0.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> backfillLinks(int limit, Long afterId) {
        Long orgId = CurrentUser.organizationId(), userId = CurrentUser.userId();
        long after = afterId == null ? 0L : afterId;
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, MAX_BATCH)));
        java.util.List<PartyRoleRef> batch = new java.util.ArrayList<>();
        for (Student s : studentRepository.findBridgedAfter(after, orgId, userId, page)) {
            after = Math.max(after, s.getId());
            batch.add(PartyRoleRef.builder().partyId(s.getPartyId())
                    .module("education").role("STUDENT").localId(s.getId()).label(s.getName()).build());
        }
        int linked = 0;
        if (partyClient != null && !batch.isEmpty()) {   // ONE call per batch, not one per row
            try {
                Integer n = partyClient.linkBulk(batch);
                linked = n == null ? 0 : n;
            } catch (Exception e) {
                LOG.warn("party link backfill batch of {} failed", batch.size(), e);
            }
        }
        return java.util.Map.of("scanned", batch.size(), "linked", linked, "lastId", after,
                "remaining", studentRepository.countBridgedAfter(after, orgId, userId));
    }
}

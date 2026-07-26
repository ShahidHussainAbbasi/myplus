package com.myplus.business_service.service;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.commerce.contracts.dto.PartyRoleRef;
import com.myplus.common.security.CurrentUser;
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
 * Party bridge (P1): links a Customer/Vender to the shared party master (party-service) and stamps its {@code party_id}
 * — so the same person resolves to ONE identity across modules. Decoupled by design: the write only PUBLISHES a
 * request; the party upsert + stamp run in an {@code AFTER_COMMIT} handler (its own {@code REQUIRES_NEW} tx), so the
 * domain transaction/connection is released BEFORE the party HTTP call (a slow/down party-service can't hold a DB
 * connection or roll the domain write back — the bottleneck/coupling fix). BEST-EFFORT: a party hiccup is logged and
 * re-attempted on the next write (the guard skips once {@code party_id} is set, so a bridged party costs nothing).
 * The handler runs on the request thread (after commit / inline when there's no tx), so {@link com.myplus.common.security
 * .GatewayIdentityForwarding} still forwards the caller's org to party-service — no runAs needed.
 */
@Service
public class PartyBridgeService {

    private static final Logger LOG = LoggerFactory.getLogger(PartyBridgeService.class);

    // Lightweight circuit breaker: after CB_THRESHOLD consecutive failures, stop calling party-service for
    // CB_COOLDOWN_MS so a sustained outage doesn't make every new-contact write pay the full client timeout.
    // Best-effort — a skipped bridge self-heals on the next write once the circuit closes.
    private static final int CB_THRESHOLD = 5;
    private static final long CB_COOLDOWN_MS = 30_000L;
    // Backfill batch cap: it reuses the hot-path party client (2s read timeout), so a batch must finish well inside
    // that. Call again with the returned cursor to continue — the link write is idempotent.
    private static final int MAX_BATCH = 200;
    private final java.util.concurrent.atomic.AtomicInteger cbFailures = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long cbOpenUntil = 0L;

    /** Identity captured at publish time so the after-commit handler needs no entity reload. */
    public record PartyBridgeRequest(String partyType, String refType, Long id,
                                     String name, String contact, String email, String address) {}

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    private PartyClient partyClient;   // null if party-service is unwired in this deployment

    @Autowired
    private CustomerRepo customerRepo;

    @Autowired
    private VenderRepo venderRepo;

    /** Queue a customer for bridging (best-effort, once). No-op if already bridged. Runs after the caller's tx commits. */
    public void bridgeCustomer(Customer c) {
        if (c == null || c.getCustomerId() == null || c.getPartyId() != null) return;
        events.publishEvent(new PartyBridgeRequest("CUSTOMER", "customer", c.getCustomerId(),
                c.getName(), c.getContact(), c.getEmail(), c.getAddress()));
    }

    /** Queue a vendor for bridging (best-effort, once). */
    public void bridgeVender(Vender v) {
        if (v == null || v.getId() == null || v.getPartyId() != null) return;
        events.publishEvent(new PartyBridgeRequest("VENDOR", "vender", v.getId(),
                v.getName(), v.getMobile(), v.getEmail(), v.getAddress()));
    }

    /** After the domain tx commits (or inline when there was none), upsert the party + stamp party_id — outside the
     *  domain transaction, so the DB connection is already released before this network call. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBridge(PartyBridgeRequest req) {
        if (partyClient == null || System.currentTimeMillis() < cbOpenUntil) return;   // unwired or circuit open
        try {
            PartyRef ref = partyClient.upsert(PartyRef.builder()
                    .partyType(req.partyType()).name(req.name()).contact(req.contact())
                    .email(req.email()).address(req.address())
                    .role(roleOf(req)).build());
            cbFailures.set(0);   // a successful call closes the breaker
            if (ref == null || ref.getId() == null) return;
            if ("customer".equals(req.refType())) customerRepo.updatePartyId(req.id(), ref.getId());
            else if ("vender".equals(req.refType())) venderRepo.updatePartyId(req.id(), ref.getId());
        } catch (Exception e) {
            if (cbFailures.incrementAndGet() >= CB_THRESHOLD) { cbOpenUntil = System.currentTimeMillis() + CB_COOLDOWN_MS; cbFailures.set(0); }
            LOG.warn("party bridge ({} {}) failed after commit — will retry on next write", req.refType(), req.id(), e);
        }
    }

    /** P4: the role link recorded alongside the identity, so the contact view can say "also a POS customer/vendor". */
    private static PartyRoleRef roleOf(PartyBridgeRequest req) {
        return PartyRoleRef.builder()
                .module("business").role(req.partyType()).localId(req.id()).label(req.name()).build();
    }

    /**
     * One-time backfill for records bridged BEFORE P4: they already carry a party_id, so the skip-guard means they
     * never bridge again and would have no role link. Owner-triggered, batched by an id cursor, and idempotent (the
     * link write is ON DUPLICATE KEY), so it can be re-run until {@code remaining} is 0. Off the hot path.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> backfillLinks(int limit, Long afterCustomerId, Long afterVenderId) {
        Long orgId = CurrentUser.organizationId(), userId = CurrentUser.userId();
        // Customers and vendors have independent id spaces, so each carries its OWN cursor — one shared cursor would
        // silently skip rows in whichever table has the higher ids.
        long afterC = afterCustomerId == null ? 0L : afterCustomerId;
        long afterV = afterVenderId == null ? 0L : afterVenderId;
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, MAX_BATCH)));
        java.util.List<PartyRoleRef> batch = new java.util.ArrayList<>();

        for (Customer c : customerRepo.findBridgedAfter(afterC, orgId, userId, page)) {
            afterC = Math.max(afterC, c.getCustomerId());
            batch.add(PartyRoleRef.builder().partyId(c.getPartyId())
                    .module("business").role("CUSTOMER").localId(c.getCustomerId()).label(c.getName()).build());
        }
        for (Vender v : venderRepo.findBridgedAfter(afterV, orgId, userId, page)) {
            afterV = Math.max(afterV, v.getId());
            batch.add(PartyRoleRef.builder().partyId(v.getPartyId())
                    .module("business").role("VENDOR").localId(v.getId()).label(v.getName()).build());
        }

        int linked = sendBatch(batch);
        long remaining = customerRepo.countBridgedAfter(afterC, orgId, userId)
                       + venderRepo.countBridgedAfter(afterV, orgId, userId);
        return java.util.Map.of("scanned", batch.size(), "linked", linked,
                "lastCustomerId", afterC, "lastVenderId", afterV, "remaining", remaining);
    }

    /** ONE call per batch — a per-row call would make backfilling a large customer table N round trips. */
    private int sendBatch(java.util.List<PartyRoleRef> batch) {
        if (partyClient == null || batch.isEmpty()) return 0;
        try {
            Integer linked = partyClient.linkBulk(batch);
            return linked == null ? 0 : linked;
        } catch (Exception e) {
            LOG.warn("party link backfill batch of {} failed", batch.size(), e);
            return 0;
        }
    }
}

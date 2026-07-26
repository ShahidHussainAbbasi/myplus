package com.myplus.business_service.service;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
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
                    .email(req.email()).address(req.address()).build());
            cbFailures.set(0);   // a successful call closes the breaker
            if (ref == null || ref.getId() == null) return;
            if ("customer".equals(req.refType())) customerRepo.updatePartyId(req.id(), ref.getId());
            else if ("vender".equals(req.refType())) venderRepo.updatePartyId(req.id(), ref.getId());
        } catch (Exception e) {
            if (cbFailures.incrementAndGet() >= CB_THRESHOLD) { cbOpenUntil = System.currentTimeMillis() + CB_COOLDOWN_MS; cbFailures.set(0); }
            LOG.warn("party bridge ({} {}) failed after commit — will retry on next write", req.refType(), req.id(), e);
        }
    }
}

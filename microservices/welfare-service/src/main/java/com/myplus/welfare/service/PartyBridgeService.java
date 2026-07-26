package com.myplus.welfare.service;

import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.welfare.entity.Donator;
import com.myplus.welfare.repository.DonatorRepo;
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
 * Party bridge (P3): links a Donator to the shared party master (party-service). Decoupled: the write only PUBLISHES a
 * request; the party upsert + stamp run in an {@code AFTER_COMMIT} handler ({@code REQUIRES_NEW} tx), so the domain
 * transaction/connection is released BEFORE the party HTTP call (a slow/down party-service can't hold a DB connection
 * or roll the donator write back). BEST-EFFORT: a hiccup is logged and re-attempted on the next write; the guard skips
 * once {@code party_id} is set. The handler runs on the request thread, so the caller's org is still forwarded.
 */
@Service
public class PartyBridgeService {

    private static final Logger LOG = LoggerFactory.getLogger(PartyBridgeService.class);

    // Lightweight circuit breaker (see business PartyBridgeService): skip party calls for a cooldown after a run of
    // failures, so a sustained party outage doesn't make every new-donator write pay the full client timeout.
    private static final int CB_THRESHOLD = 5;
    private static final long CB_COOLDOWN_MS = 30_000L;
    private final java.util.concurrent.atomic.AtomicInteger cbFailures = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long cbOpenUntil = 0L;

    /** Identity captured at publish time so the after-commit handler needs no entity reload. */
    public record PartyBridgeRequest(Long id, String name, String contact, String address) {}

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    private PartyClient partyClient;   // null if party-service is unwired in this deployment

    @Autowired
    private DonatorRepo donatorRepo;

    /** Queue a donator for bridging (best-effort, once). No-op if already bridged. Runs after the caller's tx commits. */
    public void bridgeDonator(Donator d) {
        if (d == null || d.getId() == null || d.getPartyId() != null) return;
        events.publishEvent(new PartyBridgeRequest(d.getId(), d.getName(), d.getMobile(), d.getAddress()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBridge(PartyBridgeRequest req) {
        if (partyClient == null || System.currentTimeMillis() < cbOpenUntil) return;   // unwired or circuit open
        try {
            PartyRef ref = partyClient.upsert(PartyRef.builder()
                    .partyType("DONOR").name(req.name()).contact(req.contact()).address(req.address()).build());
            cbFailures.set(0);   // a successful call closes the breaker
            if (ref != null && ref.getId() != null) donatorRepo.updatePartyId(req.id(), ref.getId());
        } catch (Exception e) {
            if (cbFailures.incrementAndGet() >= CB_THRESHOLD) { cbOpenUntil = System.currentTimeMillis() + CB_COOLDOWN_MS; cbFailures.set(0); }
            LOG.warn("party bridge (donator {}) failed after commit — will retry on next write", req.id(), e);
        }
    }
}

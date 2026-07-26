package com.myplus.marketplace.service;

import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.commerce.contracts.dto.PartyRoleRef;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.marketplace.entity.StorefrontCustomer;
import com.myplus.marketplace.repository.StorefrontCustomerRepository;
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
 * Party bridge (P3): links a marketplace StorefrontCustomer (online shopper) to the shared party master so an online
 * shopper dedupes to their POS customer / other identities by email. UNLIKE the other four bridges, storefront
 * register is ANONYMOUS (no authenticated identity to forward), so the after-commit handler wraps the party call in
 * {@code runAs(STOREFRONT_USER, orgId)} — the same synthetic identity the storefront order/cart saga uses — to scope
 * the upsert to the store's org. Decoupled (AFTER_COMMIT, off the register tx), best-effort, one-time (skip guard),
 * with the shared lightweight breaker.
 */
@Service
public class PartyBridgeService {

    private static final Logger LOG = LoggerFactory.getLogger(PartyBridgeService.class);
    private static final Long STOREFRONT_USER = 0L;   // synthetic identity for anonymous storefront → backend calls

    // Lightweight circuit breaker (see business PartyBridgeService): skip party calls for a cooldown after a run of
    // failures, so a sustained party outage doesn't make every registration pay the full client timeout.
    private static final int CB_THRESHOLD = 5;
    private static final long CB_COOLDOWN_MS = 30_000L;
    private final java.util.concurrent.atomic.AtomicInteger cbFailures = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long cbOpenUntil = 0L;

    /** Identity captured at publish time (+ orgId, since there's no authenticated context to read it from). */
    public record PartyBridgeRequest(Long id, Long orgId, String name, String email) {}

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    private PartyClient partyClient;   // null if party-service is unwired in this deployment

    @Autowired
    private StorefrontCustomerRepository repo;

    /** Queue a storefront customer for bridging (best-effort, once). Runs after the register tx commits. */
    public void bridgeStorefrontCustomer(StorefrontCustomer c) {
        if (c == null || c.getId() == null || c.getPartyId() != null || c.getOrganizationId() == null) return;
        events.publishEvent(new PartyBridgeRequest(c.getId(), c.getOrganizationId(), c.getName(), c.getEmail()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBridge(PartyBridgeRequest req) {
        if (partyClient == null || System.currentTimeMillis() < cbOpenUntil) return;   // unwired or circuit open
        try {
            // Anonymous context → runAs the store's org so party-service scopes the upsert correctly.
            java.util.concurrent.atomic.AtomicReference<PartyRef> out = new java.util.concurrent.atomic.AtomicReference<>();
            GatewayIdentityForwarding.runAs(STOREFRONT_USER, req.orgId(), () -> out.set(partyClient.upsert(
                    PartyRef.builder()
                            .partyType("CUSTOMER").name(req.name()).email(req.email())
                            .role(PartyRoleRef.builder()   // P4: identity AND role link in one call
                                    .module("marketplace").role("CUSTOMER").localId(req.id()).label(req.name()).build())
                            .build())));
            cbFailures.set(0);   // a successful call closes the breaker
            PartyRef ref = out.get();
            if (ref != null && ref.getId() != null) repo.updatePartyId(req.id(), ref.getId());
        } catch (Exception e) {
            if (cbFailures.incrementAndGet() >= CB_THRESHOLD) { cbOpenUntil = System.currentTimeMillis() + CB_COOLDOWN_MS; cbFailures.set(0); }
            LOG.warn("party bridge (storefront customer {}) failed after commit — will retry on next write", req.id(), e);
        }
    }
}

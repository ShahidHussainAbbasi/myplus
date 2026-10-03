package com.myplus.finance.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.finance.entity.PayableBalanceOutbox;
import com.myplus.finance.repository.PayableBalanceOutboxRepository;
import com.myplus.finance.repository.PayableDocRepository;

import jakarta.annotation.PostConstruct;

/**
 * FP-4c — tells business-service, per supplier, what only finance knows: the still-open EXPENSE BILLS and the
 * ADVANCE (paid ahead). business stamps them on the supplier, so its hot paths (the purchase credit-limit check, the
 * supplier list) read a local column and never call finance synchronously (stamp at write, performance standard).
 *
 * <h3>A transactional outbox that carries only WHICH supplier</h3>
 * {@link #enqueue} writes a row in the subledger change's own transaction; the figures are computed when the row is
 * SENT, so a retry after later changes sends the newest figures, and business keeps the newest by version.
 */
@Service
public class PayableBalanceService {

    public record Enqueued(Long id) {}

    /** The figures business stamps. version = when they were computed (business keeps the newest). */
    public record Balance(String partyType, Long partyId, BigDecimal otherOpen, BigDecimal advance, long version) {}

    private final PayableBalanceOutboxRepository outbox;
    private final PayableDocRepository docs;
    private final OutboxRelay relay;
    private final ApplicationEventPublisher events;
    private final RestClient business;
    private OutboxDelivery<PayableBalanceOutbox> channel;

    public PayableBalanceService(PayableBalanceOutboxRepository outbox, PayableDocRepository docs, OutboxRelay relay,
                                 ApplicationEventPublisher events, @LoadBalanced RestClient.Builder builder,
                                 @Value("${finance.business-url:http://business-service}") String businessUrl) {
        this.outbox = outbox;
        this.docs = docs;
        this.relay = relay;
        this.events = events;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(5000);
        this.business = builder.clone().baseUrl(businessUrl).requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor()).build();
    }

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "PAYABLE-BALANCE"; }
            public boolean available() { return true; }
            public Optional<PayableBalanceOutbox> find(Long id) { return outbox.findById(id); }
            public List<PayableBalanceOutbox> pending() { return outbox.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public PayableBalanceOutbox save(PayableBalanceOutbox e) { return outbox.save(e); }
            public void send(PayableBalanceOutbox e) {
                Balance b = figures(e.getOrganizationId(), e.getPartyType(), e.getPartyId());
                // business authenticates only a call that names a user; a seeded notice has none (see anyUserOf)
                Long actAs = e.getUserId() != null ? e.getUserId() : outbox.anyUserOf(e.getOrganizationId());
                if (actAs == null) throw new IllegalStateException("No user of organization " + e.getOrganizationId() + " to act as yet");
                GatewayIdentityForwarding.runAs(actAs, e.getOrganizationId(), () ->
                        business.post().uri("/internal/business/payable-balances").contentType(MediaType.APPLICATION_JSON)
                                .body(List.of(b)).retrieve().toBodilessEntity());
            }
        };
    }

    /** Queue a notice for each supplier, in the CALLER's transaction; delivered after it commits. */
    public void enqueue(Long org, Long userId, Map<String, Long> suppliersByKey) {
        for (Map.Entry<String, Long> s : suppliersByKey.entrySet()) {
            if (s.getValue() == null) continue;
            PayableBalanceOutbox o = new PayableBalanceOutbox();
            o.setOrganizationId(org);
            o.setUserId(userId);
            o.setPartyType(s.getKey().substring(0, s.getKey().indexOf(':')));
            o.setPartyId(s.getValue());
            o.setStatus("PENDING");
            o.setAttempts(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            events.publishEvent(new Enqueued(outbox.save(o).getId()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(Enqueued e) {
        relay.deliver(channel, e.id());
    }

    @Scheduled(fixedDelayString = "${finance.payable-balance.relay-delay-ms:15000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flushPending() {
        relay.flush(channel);
    }

    /** The supplier's figures NOW. */
    Balance figures(Long org, String partyType, Long partyId) {
        return figures(partyType, partyId, docs.otherOpen(org, partyType, partyId), docs.netOf(org, partyType, partyId),
                System.currentTimeMillis());
    }

    /** Pure: bills still open (never negative), and the advance = how far the supplier's net is below zero. */
    static Balance figures(String partyType, Long partyId, BigDecimal otherOpen, BigDecimal net, long version) {
        BigDecimal other = otherOpen == null ? BigDecimal.ZERO : otherOpen.max(BigDecimal.ZERO);
        BigDecimal n = net == null ? BigDecimal.ZERO : net;
        BigDecimal advance = n.signum() < 0 ? n.negate() : BigDecimal.ZERO;
        return new Balance(partyType, partyId, other, advance, version);
    }

    /** A "TYPE:id" key per supplier, so one batch of snapshots queues each supplier once. */
    static Map<String, Long> keyed(List<com.myplus.finance.dto.PayableSnapshot> snapshots) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (snapshots == null) return out;
        for (var s : snapshots) {
            if (s == null || s.getPartyId() == null) continue;
            String type = s.getPartyType() == null ? "VENDOR" : s.getPartyType();
            out.put(type + ":" + s.getPartyId(), s.getPartyId());
        }
        return out;
    }
}

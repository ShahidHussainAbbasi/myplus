package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.myplus.business_service.entity.BillApplicationOutbox;
import com.myplus.business_service.repository.BillApplicationOutboxRepo;
import com.myplus.commerce.contracts.client.ExpenseClient;
import com.myplus.commerce.contracts.dto.BillApplyRequest;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.security.GatewayIdentityForwarding;

import jakarta.annotation.PostConstruct;

/**
 * FP-5b — carries each expense bill's share of a Pay Supplier payment to expense-service (transactional outbox, the
 * drawer-expense pattern): captured in the payment's own transaction, delivered after commit, retried by a schedule,
 * dead-lettered after the relay's limit. expense-service applies it once per (payment reference, bill).
 */
@Service
public class BillApplicationOutboxService {

    public record Enqueued(Long id) {}

    private final BillApplicationOutboxRepo repo;
    private final OutboxRelay relay;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<ExpenseClient> expense;
    private OutboxDelivery<BillApplicationOutbox> channel;

    public BillApplicationOutboxService(BillApplicationOutboxRepo repo, OutboxRelay relay, ApplicationEventPublisher events,
                                        ObjectProvider<ExpenseClient> expense) {
        this.repo = repo;
        this.relay = relay;
        this.events = events;
        this.expense = expense;
    }

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "BILL-APPLICATION"; }
            public boolean available() { return expense.getIfAvailable() != null; }
            public Optional<BillApplicationOutbox> find(Long id) { return repo.findById(id); }
            public List<BillApplicationOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public BillApplicationOutbox save(BillApplicationOutbox e) { return repo.save(e); }
            public void send(BillApplicationOutbox e) {
                BillApplyRequest req = BillApplyRequest.builder().amount(e.getAmount()).clientRef(e.getClientRef())
                        .method(e.getMethod()).paidOn(e.getPaidOn()).build();
                Object[] out = new Object[1];
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> out[0] = expense.getObject().applyToBill(e.getVoucherId(), req));
                if (out[0] instanceof Map<?, ?> m && m.get("applied") != null)
                    e.setApplied(new BigDecimal(String.valueOf(m.get("applied"))));
            }
        };
    }

    /** Queue one bill's share, in the CALLER's (payment's) transaction; delivered after it commits. */
    public void enqueue(Long voucherId, BigDecimal amount, String clientRef, String method, LocalDate paidOn) {
        BillApplicationOutbox o = new BillApplicationOutbox();
        o.setOrganizationId(CurrentUser.organizationId());
        o.setUserId(CurrentUser.userId());
        o.setVoucherId(voucherId);
        o.setAmount(amount);
        o.setClientRef(clientRef);
        o.setMethod(method);
        o.setPaidOn(paidOn);
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        events.publishEvent(new Enqueued(repo.save(o).getId()));
    }

    /** Ruling 4 — a tenant that has paid purchases and bills together cannot go back to business figures. */
    public long mixedPayments(Long organizationId) {
        return organizationId == null ? 0 : repo.countByOrganizationId(organizationId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(Enqueued e) {
        relay.deliver(channel, e.id());
    }

    @Scheduled(fixedDelayString = "${bill-application.outbox.relay-delay-ms:30000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flushPending() {
        relay.flush(channel);
    }
}

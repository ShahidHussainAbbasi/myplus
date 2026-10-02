package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myplus.business_service.entity.DrawerExpenseOutbox;
import com.myplus.business_service.repository.CashMovementRepo;
import com.myplus.business_service.repository.DrawerExpenseOutboxRepo;
import com.myplus.commerce.contracts.client.ExpenseClient;
import com.myplus.commerce.contracts.dto.DrawerExpenseRequest;
import com.myplus.commerce.contracts.dto.ExpenseVoucherRef;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.GatewayIdentityForwarding;

import jakarta.annotation.PostConstruct;

/**
 * EX-3 — carries a till pay-out to expense-service (transactional outbox on the shared {@link OutboxRelay}).
 *
 * <p>Captured in the SAME transaction as the drawer movement, so a pay-out and its trip to the books commit or
 * roll back together; delivered after commit, so a slow or absent expense-service never holds the till. A pay-out
 * whose delivery fails stays PENDING and is retried by the schedule — the drawer is already right, and the books
 * catch up. On success the movement is stamped with the EXP- number it became.
 */
@Service
public class DrawerExpenseOutboxService {

    public record Enqueued(Long id) {}

    private final DrawerExpenseOutboxRepo repo;
    private final CashMovementRepo movements;
    private final OutboxRelay relay;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<ExpenseClient> expense;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OutboxDelivery<DrawerExpenseOutbox> channel;

    public DrawerExpenseOutboxService(DrawerExpenseOutboxRepo repo, CashMovementRepo movements, OutboxRelay relay,
                                      ApplicationEventPublisher events, ObjectProvider<ExpenseClient> expense) {
        this.repo = repo;
        this.movements = movements;
        this.relay = relay;
        this.events = events;
        this.expense = expense;
    }

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "DRAWER-EXPENSE"; }
            public boolean available() { return expense.getIfAvailable() != null; }
            public Optional<DrawerExpenseOutbox> find(Long id) { return repo.findById(id); }
            public List<DrawerExpenseOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public DrawerExpenseOutbox save(DrawerExpenseOutbox e) { return repo.save(e); }
            public void send(DrawerExpenseOutbox e) {
                DrawerExpenseRequest req = read(e.getPayload());
                ExpenseVoucherRef[] made = new ExpenseVoucherRef[1];
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> made[0] = expense.getObject().recordDrawerPayOut(req));
                if (made[0] != null && made[0].getVoucherNo() != null) {
                    movements.stampExpenseVoucher(e.getMovementId(), made[0].getVoucherNo());
                }
            }
        };
    }

    /** Capture the pay-out in the CALLER's transaction (the movement's). */
    public void enqueue(Long orgId, Long userId, DrawerExpenseRequest req) {
        DrawerExpenseOutbox o = new DrawerExpenseOutbox();
        o.setOrganizationId(orgId);
        o.setUserId(userId);
        o.setMovementId(req.getMovementId());
        o.setPayload(write(req));
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        events.publishEvent(new Enqueued(repo.save(o).getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(Enqueued e) {
        relay.deliver(channel, e.id());
    }

    @Scheduled(fixedDelayString = "${expense.drawer.relay-delay-ms:30000}")
    public void flushPending() {
        relay.flush(channel);
    }

    private String write(DrawerExpenseRequest r) {
        try { return json.writeValueAsString(r); }
        catch (Exception e) { throw new IllegalStateException("Could not record the pay-out for the books", e); }
    }

    DrawerExpenseRequest read(String payload) {
        try { return json.readValue(payload, DrawerExpenseRequest.class); }
        catch (Exception e) { throw new IllegalStateException("Unreadable pay-out payload", e); }
    }
}

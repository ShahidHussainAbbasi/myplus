package com.myplus.expense.service;

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
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.expense.entity.ExpenseOutbox;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseOutboxRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

import jakarta.annotation.PostConstruct;

/**
 * The transactional outbox that carries an expense to the ledger (the GL-outbox pattern, on the shared
 * {@link OutboxRelay}): captured in the voucher's own transaction, delivered after commit, retried by a schedule,
 * dead-lettered after the relay's limit.
 *
 * <h3>The voucher's posting status is STAMPED here, when finance answers</h3>
 * The relay calls {@code save()} after every attempt with the row's new state. That is the one moment the
 * answer is known, so that is where the voucher learns it: POSTED → "In the books", FAILED → "Failed" with
 * finance's reason. The UI never claims the books before this (STANDARDS §0b).
 */
@Service
public class ExpenseOutboxService {

    public record Enqueued(Long id) {}

    private final ExpenseOutboxRepo repo;
    private final ExpenseVoucherRepo vouchers;
    private final OutboxRelay relay;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<FinanceClient> finance;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OutboxDelivery<ExpenseOutbox> channel;

    public ExpenseOutboxService(ExpenseOutboxRepo repo, ExpenseVoucherRepo vouchers, OutboxRelay relay,
                                ApplicationEventPublisher events, ObjectProvider<FinanceClient> finance) {
        this.repo = repo;
        this.vouchers = vouchers;
        this.relay = relay;
        this.events = events;
        this.finance = finance;
    }

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "EXPENSE-GL"; }
            public boolean available() { return finance.getIfAvailable() != null; }
            public Optional<ExpenseOutbox> find(Long id) { return repo.findById(id); }
            public List<ExpenseOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public ExpenseOutbox save(ExpenseOutbox e) {
                ExpenseOutbox saved = repo.save(e);
                stampVoucher(saved);
                return saved;
            }
            public void send(ExpenseOutbox e) {
                PostingEventRequest req = read(e.getPayload());
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> finance.getObject().postEvent(req));
            }
        };
    }

    /** Capture the posting in the CALLER's transaction; delivery happens after it commits. */
    public void enqueue(ExpenseVoucher v, PostingEventRequest req) {
        ExpenseOutbox o = new ExpenseOutbox();
        o.setOrganizationId(v.getOrganizationId());
        o.setUserId(v.getUserId());
        o.setVoucherId(v.getId());
        o.setEventType(req.getEventType());
        o.setEventKey(req.getEventKey());
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

    @Scheduled(fixedDelayString = "${expense.outbox.relay-delay-ms:15000}")
    public void flushPending() {
        relay.flush(channel);
    }

    /**
     * POSTING event: POSTED → POSTED_GL, FAILED → FAILED (with finance's reason). A failure mid-retry keeps the
     * voucher PENDING but records the latest reason, so the owner can see why it is slow.
     * REVERSAL event: the voucher is already VOIDED; a failure is recorded as the error, never as a status that
     * would claim the expense is back in the books.
     */
    private void stampVoucher(ExpenseOutbox o) {
        boolean posting = VoucherPostings.EXPENSE.equals(o.getEventType());
        String error = o.getLastError() == null ? null : trim(o.getLastError());
        if ("POSTED".equals(o.getStatus())) {
            if (posting) vouchers.stampPosting(o.getVoucherId(), ExpenseVoucher.PS_POSTED_GL, null);
        } else if ("FAILED".equals(o.getStatus())) {
            if (posting) vouchers.stampPosting(o.getVoucherId(), ExpenseVoucher.PS_FAILED, error);
            else vouchers.stampPosting(o.getVoucherId(), ExpenseVoucher.PS_POSTED_GL, "Void not yet in the books: " + error);
        } else if (posting && error != null) {
            vouchers.stampPosting(o.getVoucherId(), ExpenseVoucher.PS_PENDING, error);
        }
    }

    private static String trim(String s) { return s.length() > 450 ? s.substring(0, 450) : s; }

    private String write(PostingEventRequest r) {
        try { return json.writeValueAsString(r); }
        catch (Exception e) { throw new IllegalStateException("Could not record the ledger posting", e); }
    }

    PostingEventRequest read(String payload) {
        try { return json.readValue(payload, PostingEventRequest.class); }
        catch (Exception e) { throw new IllegalStateException("Unreadable ledger posting payload", e); }
    }
}

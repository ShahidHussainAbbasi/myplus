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
                if (VoucherPostings.PAYABLE.equals(e.getEventType())) { sendPayable(e); return; }
                // EX-1b — never book an expense whose voucher was voided before it reached the books. A FAILED
                // posting voids with no reversal, so re-sending it (a "Post again", or the operator's re-drive of
                // every FAILED row) would put a voided expense in the books with nothing to take it out.
                if (VoucherPostings.EXPENSE.equals(e.getEventType())) {
                    ExpenseVoucher v = vouchers.findById(e.getVoucherId()).orElse(null);
                    if (v == null || !ExpenseVoucher.POSTED.equals(v.getStatus()))
                        throw new NotSent("Not sent: this expense was voided before it reached the books.");
                }
                PostingEventRequest req = read(e.getPayload());
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> finance.getObject().postEvent(req));
            }

            /** EX-1b — finance ANSWERED and refused (a closed period, a rule): retrying cannot help, so the row is
             *  dead-lettered at once and the owner sees why. An outage, a timeout or a 401/403 (a secret not yet
             *  loaded) is not a refusal and keeps being retried. */
            @Override
            public boolean permanent(Exception failure) {
                if (failure instanceof NotSent) return true;
                if (failure instanceof org.springframework.web.client.HttpClientErrorException h) {
                    int code = h.getStatusCode().value();
                    return code == 400 || code == 409 || code == 422;
                }
                return false;
            }

            /** The books' own words ("This period is closed (locked through …)"), not the transport's wrapper. */
            @Override
            public String describe(Exception failure) {
                if (failure instanceof org.springframework.web.client.HttpStatusCodeException h) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode m = json.readTree(h.getResponseBodyAsString()).get("message");
                        if (m != null && !m.asText().isBlank()) return m.asText();
                    } catch (Exception ignored) { /* not JSON: fall through */ }
                    return h.getStatusCode().value() + " " + h.getStatusText();
                }
                return String.valueOf(failure.getMessage());
            }
        };
    }

    /** A posting that must not be sent (see send): permanent by definition. */
    static final class NotSent extends RuntimeException {
        NotSent(String message) { super(message); }
    }

    /**
     * EX-1b — put this voucher's FAILED rows of the given kinds back in the queue (PENDING, attempts 0) and deliver
     * them after the caller commits. Each row keeps its event key, so finance books it once however often it is sent.
     * Returns how many rows were re-queued.
     */
    public int redrive(Long voucherId, java.util.Set<String> eventTypes) {
        int n = 0;
        for (ExpenseOutbox o : repo.findByVoucherIdAndStatus(voucherId, "FAILED")) {
            if (!eventTypes.contains(o.getEventType())) continue;
            o.setStatus("PENDING");
            o.setAttempts(0);
            o.setLastError(null);
            o.setUpdatedAt(LocalDateTime.now());
            events.publishEvent(new Enqueued(repo.save(o).getId()));
            n++;
        }
        return n;
    }

    /** The channel, for tests that drive it through the shared relay. */
    OutboxDelivery<ExpenseOutbox> channel() { return channel; }

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

    /**
     * FP-3 — tell finance's payables subledger the bill changed. The row carries only WHICH bill: the snapshot is
     * read at send time, so a retry after later changes sends the newest figures, never stale ones. One row per change
     * (unique key), each harmless to repeat — finance upserts by (source, sourceRef) and ignores an older version.
     */
    public void enqueuePayable(ExpenseVoucher v) {
        ExpenseOutbox o = new ExpenseOutbox();
        o.setOrganizationId(v.getOrganizationId());
        o.setUserId(v.getUserId());
        o.setVoucherId(v.getId());
        o.setEventType(VoucherPostings.PAYABLE);
        o.setEventKey("EXPB-" + v.getOrganizationId() + "-" + v.getId() + "-" + java.util.UUID.randomUUID().toString().substring(0, 13));
        o.setPayload("{\"voucherId\":" + v.getId() + "}");
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        events.publishEvent(new Enqueued(repo.save(o).getId()));
    }

    private void sendPayable(ExpenseOutbox e) {
        ExpenseVoucher v = vouchers.findById(e.getVoucherId())
                .orElseThrow(() -> new IllegalStateException("Bill " + e.getVoucherId() + " no longer exists"));
        var snapshot = VoucherPostings.payable(v);
        GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                () -> finance.getObject().upsertPayables(List.of(snapshot)));
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
        // FP-3 — a subledger update says nothing about the voucher's journal; it must never stamp postingStatus.
        if (VoucherPostings.PAYABLE.equals(o.getEventType())) return;
        boolean posting = VoucherPostings.EXPENSE.equals(o.getEventType());
        String error = o.getLastError() == null ? null : trim(o.getLastError());
        if ("POSTED".equals(o.getStatus())) {
            // EX-1b — a reversal that lands (on a re-drive) clears its "Void not yet in the books"; before, nothing did.
            vouchers.stampPosting(o.getVoucherId(), ExpenseVoucher.PS_POSTED_GL, null);
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

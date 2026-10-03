package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myplus.business_service.entity.PayableBackfill;
import com.myplus.business_service.entity.PayableOutbox;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.repository.PayableBackfillRepo;
import com.myplus.business_service.repository.PayableOutboxRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PayableNote;
import com.myplus.commerce.contracts.dto.PayableSnapshot;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.GatewayIdentityForwarding;

import jakarta.annotation.PostConstruct;

/**
 * FP-2 — reports every change to a supplier purchase into finance's payables subledger (a shadow of business,
 * which stays authoritative until FP-4/FP-5).
 *
 * <h3>How a change becomes one outbox row</h3>
 * {@link PurchasePayableListener} hands every saved purchase to {@link #changed}. The figures are captured at that
 * moment (no re-read) into a transaction-scoped map, last write per purchase wins; just BEFORE COMMIT one outbox row
 * per purchase is written into the same transaction, and AFTER COMMIT it is delivered. A purchase edited three times
 * in one save is reported once, with its final figures. A rollback reports nothing.
 *
 * <h3>Why a listener and not seven call sites</h3>
 * Seven writers change what a supplier is owed (add, edit, return, opening AP, payment, void, recompute) and every
 * one goes through a JPA save — the listener sees them all, and a writer added next year is covered without
 * anyone remembering this class exists. Purchases are never bulk-deleted (they are voided), so nothing bypasses it.
 */
@Service
public class PayableOutboxService {

    private static final Logger LOG = LoggerFactory.getLogger(PayableOutboxService.class);
    private static final String TX_KEY = PayableOutboxService.class.getName() + ".changed";
    static final int BACKFILL_BATCH = 200;
    /** FP-4a — the snapshot generation the backfill replays: 2 = with the statement trail (issued amount, debit notes). */
    static final int GENERATION = 2;
    /** payable_outbox.payload is VARCHAR(8000) (V73). */
    static final int MAX_PAYLOAD = 8000;

    private final PayableOutboxRepo repo;
    private final PayableBackfillRepo backfills;
    private final PurchaseRepo purchases;
    private final VenderRepo vendors;
    private final com.myplus.business_service.repository.PurchaseReturnRepo returns;
    private final OutboxRelay relay;
    private final ObjectProvider<FinanceClient> finance;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OutboxDelivery<PayableOutbox> channel;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public PayableOutboxService(PayableOutboxRepo repo, PayableBackfillRepo backfills, PurchaseRepo purchases,
                                VenderRepo vendors, com.myplus.business_service.repository.PurchaseReturnRepo returns,
                                OutboxRelay relay, ObjectProvider<FinanceClient> finance, TransactionTemplate tx) {
        this.returns = returns;
        this.repo = repo;
        this.backfills = backfills;
        this.purchases = purchases;
        this.vendors = vendors;
        this.relay = relay;
        this.finance = finance;
        this.tx = tx;
    }

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "PAYABLES"; }
            public boolean available() { return finance.getIfAvailable() != null; }
            public Optional<PayableOutbox> find(Long id) { return repo.findById(id); }
            public List<PayableOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public PayableOutbox save(PayableOutbox e) { return repo.save(e); }
            public void send(PayableOutbox e) {
                PayableSnapshot s = read(e.getPayload());
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> finance.getObject().upsertPayables(List.of(s)));
            }
        };
    }

    // ── live changes ────────────────────────────────────────────────────────────────────────────

    /**
     * Called by the entity listener for every saved purchase. Supplier purchases only; cash buys owe nothing.
     *
     * <h3>⚠ WHY A HIBERNATE BEFORE-COMPLETION PROCESS, NOT SPRING's beforeCommit (found by the integration test)</h3>
     * Purchase ids come from a TABLE generator (purch_seq), so even a NEW purchase is inserted only when the session
     * flushes — and at the end of a transaction that flush IS the commit. Hibernate fires @PostPersist/@PostUpdate
     * DURING it, after Spring has already run every {@code beforeCommit}; a synchronization registered here would
     * miss its beforeCommit entirely (it did: rows were never written, afterCompletion still fired).
     *
     * Hibernate's own {@code BeforeTransactionCompletionProcess} runs after its final flush and before the JDBC
     * commit, on the same connection — the mechanism Hibernate Envers uses for audit rows. So the outbox row is
     * written there, by plain JDBC, atomically with the purchase. Delivery stays on Spring's {@code afterCommit},
     * which does run for a synchronization registered during the commit.
     */
    @SuppressWarnings("unchecked")
    public void changed(Purchase p) {
        if (p == null || p.getPurchaseId() == null || p.getVenderId() == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueAndDeliver(List.of(capture(p)));   // no transaction at all (none exist today): never lose it
            return;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(TX_KEY);
        if (pending == null) {
            pending = new Pending();
            TransactionSynchronizationManager.bindResource(TX_KEY, pending);
            final Pending mine = pending;
            org.hibernate.engine.spi.SessionImplementor session =
                    entityManager.unwrap(org.hibernate.engine.spi.SessionImplementor.class);
            session.getActionQueue().registerProcess(
                    (org.hibernate.action.spi.BeforeTransactionCompletionProcess) s -> writeRowsJdbc(s, mine));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    for (Long id : mine.written) deliverQuietly(id);
                }
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TX_KEY);
                }
            });
        }
        pending.byPurchase.put(p.getPurchaseId(), capture(p));   // last write in the transaction wins
    }

    /** One transaction's captured changes, and the outbox row ids written for them. */
    static final class Pending {
        final Map<Long, Captured> byPurchase = new LinkedHashMap<>();
        final List<Long> written = new ArrayList<>();
    }

    /** Inside Hibernate's before-completion, on the purchase's own connection: one outbox row per purchase. */
    private void writeRowsJdbc(org.hibernate.engine.spi.SessionImplementor session, Pending pending) {
        session.doWork(conn -> {
            Map<Long, String> names = new HashMap<>();
            try (java.sql.PreparedStatement name = conn.prepareStatement("SELECT name FROM vender WHERE vender_id = ?");
                 java.sql.PreparedStatement dn = conn.prepareStatement(
                         "SELECT debit_note_no, purchase_invoice_no, dated, amount FROM purchase_return "
                                 + "WHERE purchase_id = ? ORDER BY id");
                 java.sql.PreparedStatement ins = conn.prepareStatement(
                         "INSERT INTO payable_outbox (organization_id, user_id, purchase_id, payload, status, attempts, "
                                 + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', 0, NOW(), NOW())",
                         java.sql.Statement.RETURN_GENERATED_KEYS)) {
                for (Captured c : pending.byPurchase.values()) {
                    PayableSnapshot s = c.snapshot();
                    s.setPartyName(names.computeIfAbsent(s.getPartyId(), vid -> {
                        try {
                            name.setLong(1, vid);
                            try (java.sql.ResultSet rs = name.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
                        } catch (java.sql.SQLException e) { return null; }
                    }));
                    dn.setLong(1, Long.parseLong(s.getSourceRef()));
                    List<PayableNote> notes = new ArrayList<>();
                    try (java.sql.ResultSet rs = dn.executeQuery()) {
                        while (rs.next()) {
                            java.sql.Timestamp at = rs.getTimestamp(3);
                            notes.add(note(rs.getString(1), rs.getString(2),
                                    at == null ? null : at.toLocalDateTime(), rs.getBigDecimal(4)));
                        }
                    }
                    s.setNotes(notes);
                    ins.setObject(1, c.orgId());
                    ins.setObject(2, c.userId());
                    ins.setLong(3, Long.parseLong(s.getSourceRef()));
                    ins.setString(4, fitted(s));
                    ins.executeUpdate();
                    try (java.sql.ResultSet keys = ins.getGeneratedKeys()) {
                        if (keys.next()) pending.written.add(keys.getLong(1));
                    }
                }
            }
        });
    }

    /** The purchase's figures at this moment. bill = paid − due (business stores due = paid − bill). */
    record Captured(Long orgId, Long userId, PayableSnapshot snapshot) {}

    Captured capture(Purchase p) {
        BigDecimal paid = p.getPaidAmount() == null ? BigDecimal.ZERO : p.getPaidAmount();
        BigDecimal due = p.getDueAmount() == null ? BigDecimal.ZERO : p.getDueAmount();
        PayableSnapshot s = PayableSnapshot.builder()
                .source("PURCHASE").sourceRef(String.valueOf(p.getPurchaseId()))
                .sourceVersion(System.currentTimeMillis())
                .partyType("VENDOR").partyId(p.getVenderId())
                .docNo(p.getPurchaseInvoiceNo())
                .docDate(p.getDated() == null ? null : p.getDated().toLocalDate())
                .amount(paid.subtract(due).abs())
                .paid(paid)
                .voided("VOID".equalsIgnoreCase(p.getStatus()))
                // FP-4a — the bill AS ISSUED: exactly the BILL line business's statement prints
                .issuedAmount(p.getIssuedTotal() != null ? p.getIssuedTotal()
                        : nz(p.getTotalAmount()).add(nz(p.getTaxAmount())))
                .build();
        return new Captured(p.getOrganizationId(), p.getUserId(), s);
    }

    private List<Long> writeRows(List<Captured> all) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (Captured c : all) {
            PayableSnapshot s = c.snapshot();
            s.setPartyName(names.computeIfAbsent(s.getPartyId(),
                    vid -> vendors.findById(vid).map(v -> v.getName()).orElse(null)));
            s.setNotes(notesOf(Long.valueOf(s.getSourceRef())));
            PayableOutbox o = new PayableOutbox();
            o.setOrganizationId(c.orgId());
            o.setUserId(c.userId());
            o.setPurchaseId(Long.valueOf(s.getSourceRef()));
            o.setPayload(fitted(s));
            o.setStatus("PENDING");
            o.setAttempts(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            ids.add(repo.save(o).getId());
        }
        return ids;
    }

    private void enqueueAndDeliver(List<Captured> all) {
        List<Long> ids = tx.execute(st -> writeRows(all));
        if (ids != null) ids.forEach(this::deliverQuietly);
    }

    /** After commit, in its own transaction: a failure leaves the row PENDING for the schedule. */
    private void deliverQuietly(Long id) {
        try {
            tx.executeWithoutResult(st -> relay.deliver(channel, id));
        } catch (Exception e) {
            LOG.warn("payable outbox {} not delivered yet; the schedule will retry", id, e);
        }
    }

    @Scheduled(fixedDelayString = "${payables.outbox.relay-delay-ms:30000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flushPending() {
        relay.flush(channel);
    }

    // ── the automatic backfill (ruling: all tenants) ───────────────────────────────────────────

    /**
     * Replays each tenant's existing supplier purchases into finance once, in batches of {@value #BACKFILL_BATCH}
     * (one call per batch — not one per purchase). Runs on the schedule until every tenant has a marker; a tenant
     * whose batch fails is simply tried again next round. finance's intake is idempotent, so a repeat changes nothing.
     */
    @Scheduled(initialDelayString = "${payables.backfill.initial-delay-ms:60000}",
               fixedDelayString = "${payables.backfill.delay-ms:300000}")
    public void backfillPending() {
        if (finance.getIfAvailable() == null) return;
        for (Long org : purchases.findOrgsWithSupplierPurchases()) {
            if (org == null) continue;
            PayableBackfill b = backfills.findById(org).orElse(null);
            if (b != null && b.getGeneration() != null && b.getGeneration() >= GENERATION) continue;
            try {
                int n = backfillOrg(org);
                if (b == null) {
                    b = new PayableBackfill();
                    b.setOrganizationId(org);
                }
                b.setDocuments(n);
                b.setGeneration(GENERATION);
                b.setDoneAt(LocalDateTime.now());
                backfills.save(b);
                LOG.info("payables backfill (generation {}): org {} → {} documents reported to finance", GENERATION, org, n);
            } catch (Exception e) {
                LOG.warn("payables backfill for org {} failed; will retry next round", org, e);
            }
        }
    }

    int backfillOrg(Long org) {
        int page = 0, total = 0;
        while (true) {
            List<Purchase> batch = purchases.findSupplierPurchasesByOrg(org, PageRequest.of(page++, BACKFILL_BATCH));
            if (batch.isEmpty()) return total;
            Map<Long, String> names = new HashMap<>();
            List<PayableSnapshot> snaps = new ArrayList<>();
            Long userId = null;
            for (Purchase p : batch) {
                Captured c = capture(p);
                c.snapshot().setNotes(notesOf(p.getPurchaseId()));
                c.snapshot().setPartyName(names.computeIfAbsent(p.getVenderId(),
                        vid -> vendors.findById(vid).map(v -> v.getName()).orElse(null)));
                snaps.add(c.snapshot());
                if (userId == null) userId = p.getUserId();
            }
            final Long uid = userId;
            GatewayIdentityForwarding.runAs(uid, org, () -> finance.getObject().upsertPayables(snaps));
            total += snaps.size();
        }
    }

    /** A debit note as the statement prints it: its own number, else the bill's (business's rule). */
    static PayableNote note(String noteNo, String invoiceNo, LocalDateTime dated, java.math.BigDecimal amount) {
        return PayableNote.builder()
                .noteNo(noteNo != null ? noteNo : invoiceNo)
                .noteDate(dated == null ? null : dated.toLocalDate())
                .amount(amount == null ? java.math.BigDecimal.ZERO : amount)
                .build();
    }

    private List<PayableNote> notesOf(Long purchaseId) {
        List<PayableNote> out = new ArrayList<>();
        for (var r : returns.findByPurchaseIdOrderByIdAsc(purchaseId))
            out.add(note(r.getDebitNoteNo(), r.getPurchaseInvoiceNo(), r.getDated(), r.getAmount()));
        return out;
    }

    /**
     * The payload, guaranteed to fit its column. This row is written INSIDE the purchase's commit, so an oversized
     * payload would fail the purchase itself — never acceptable for a report to finance. A trail too long to carry is
     * sent as "not sent" (null): finance keeps the notes it has, the balance figures still arrive, and it is logged.
     */
    String fitted(PayableSnapshot s) {
        String out = write(s);
        if (out.length() <= MAX_PAYLOAD) return out;
        LOG.warn("payable snapshot for purchase {} carries {} debit notes ({} chars) — sent without its note trail",
                s.getSourceRef(), s.getNotes() == null ? 0 : s.getNotes().size(), out.length());
        s.setNotes(null);
        return write(s);
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) { return v == null ? java.math.BigDecimal.ZERO : v; }

    private String write(PayableSnapshot s) {
        try { return json.writeValueAsString(s); }
        catch (Exception e) { throw new IllegalStateException("Could not record the payable", e); }
    }

    PayableSnapshot read(String payload) {
        try { return json.readValue(payload, PayableSnapshot.class); }
        catch (Exception e) { throw new IllegalStateException("Unreadable payable payload", e); }
    }
}

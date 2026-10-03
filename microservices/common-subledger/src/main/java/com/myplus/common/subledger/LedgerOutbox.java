package com.myplus.common.subledger;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxEntry;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * FP-5a — a settlement's payment reaches finance's ledger EXACTLY ONCE, or not at all.
 *
 * <h3>What it replaces</h3>
 * {@code SubledgerService.settle} used to call finance synchronously inside the caller's transaction and swallow any
 * failure ("best-effort, reconcile later" — and nothing reconciled). Finance down meant documents marked paid with no
 * voucher and no journal; finance up but the caller rolled back meant a voucher with nothing applied, and a retry that
 * paid twice.
 *
 * <h3>How</h3>
 * <ol>
 *   <li>{@link #enqueue} inserts the WHOLE request (payload JSON) into {@code ledger_payment_outbox} with JDBC, in the
 *       caller's transaction — the documents and the ledger request are durable together or not at all. JDBC, not an
 *       entity, so this one class serves every service; each service only adds the table to its own Flyway.</li>
 *   <li>After commit, in the committing thread, it is delivered once, so a screen still gets its voucher number when
 *       finance is up ({@link #voucherFor}).</li>
 *   <li>Not delivered → stays PENDING; {@link #flushPending} retries every minute; the shared {@link OutboxRelay}
 *       dead-letters after its limit. Every request carries a {@code clientRef}; finance answers a repeat with the
 *       FIRST payment (V13 unique index), so a resend after a lost answer can never pay twice.</li>
 * </ol>
 */
public class LedgerOutbox {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerOutbox.class);
    static final String TABLE = "ledger_payment_outbox";

    private final JdbcTemplate jdbc;
    private final ObjectProvider<FinanceClient> finance;
    private final OutboxRelay relay;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final OutboxDelivery<Row> channel;

    public LedgerOutbox(JdbcTemplate jdbc, ObjectProvider<FinanceClient> finance, OutboxRelay relay) {
        this.jdbc = jdbc;
        this.finance = finance;
        this.relay = relay;
        this.channel = new OutboxDelivery<>() {
            public String name() { return "LEDGER-PAYMENT"; }
            public boolean available() { return finance.getIfAvailable() != null; }
            public Optional<Row> find(Long id) { return LedgerOutbox.this.find(id); }
            public List<Row> pending() { return LedgerOutbox.this.pendingRows(); }
            public Row save(Row r) { LedgerOutbox.this.update(r); return r; }
            public void send(Row r) {
                PaymentRecordRequest req = read(r.payload);
                GatewayIdentityForwarding.runAs(r.userId, r.organizationId, () -> {
                    PaymentRecordResult res = finance.getObject().recordPayment(req);
                    r.receiptNo = res == null ? null : res.getReceiptNo();
                });
            }
        };
    }

    /**
     * Queue {@code req} (which must carry a clientRef) in the CALLER's transaction; delivered after it commits.
     * Outside any transaction (none of today's callers) it is written and delivered at once.
     */
    public void enqueue(PaymentRecordRequest req) {
        if (req == null || req.getClientRef() == null || req.getClientRef().isBlank())
            throw new IllegalArgumentException("A ledger payment needs a clientRef");
        Long org = CurrentUser.organizationId();
        Long user = CurrentUser.userId();
        String payload = write(req);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO " + TABLE + " (organization_id, user_id, client_ref, "
                    + "payload, status, attempts, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, org);
            ps.setObject(2, user);
            ps.setString(3, req.getClientRef());
            ps.setString(4, payload);
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            return ps;
        }, keys);
        Long id = keys.getKey() == null ? null : keys.getKey().longValue();
        if (id == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deliverQuietly(id); }
            });
        } else {
            deliverQuietly(id);
        }
    }

    /** The voucher/receipt number finance gave this settlement, once delivered; empty while it is still pending. */
    public Optional<String> voucherFor(String clientRef) {
        if (clientRef == null) return Optional.empty();
        List<String> r = jdbc.query("SELECT receipt_no FROM " + TABLE + " WHERE client_ref = ?",
                (rs, i) -> rs.getString(1), clientRef);
        return r.isEmpty() ? Optional.empty() : Optional.ofNullable(r.get(0));
    }

    /**
     * For a caller's response map, AFTER its transaction committed: put the voucher number under {@code numberKey} if
     * finance has answered; otherwise mark {@code voucherPending} — the payment is recorded and its number follows.
     * The response must carry the settlement's reference under {@code ledgerRef}.
     */
    public void fill(java.util.Map<String, Object> response, String numberKey) {
        if (response == null || response.get(numberKey) != null) return;
        Object ref = response.get("ledgerRef");
        String no = ref == null ? null : voucherFor(String.valueOf(ref)).orElse(null);
        if (no != null) response.put(numberKey, no);
        else if (ref != null) response.put("voucherPending", true);
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay-delay-ms:60000}", initialDelay = 30000)
    public void flushPending() {
        try {
            relay.flush(channel);
        } catch (Exception e) {
            LOG.warn("ledger outbox flush failed; next round retries", e);
        }
    }

    private void deliverQuietly(Long id) {
        try {
            relay.deliver(channel, id);
        } catch (Exception e) {
            LOG.warn("ledger payment {} not delivered yet; the schedule retries", id, e);
        }
    }

    // ── JDBC row access ─────────────────────────────────────────────────────────────────────────

    private Optional<Row> find(Long id) {
        List<Row> r = jdbc.query("SELECT id, organization_id, user_id, client_ref, payload, status, attempts, last_error, "
                + "receipt_no FROM " + TABLE + " WHERE id = ?", (rs, i) -> Row.of(rs), id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    private List<Row> pendingRows() {
        return jdbc.query("SELECT id, organization_id, user_id, client_ref, payload, status, attempts, last_error, "
                + "receipt_no FROM " + TABLE + " WHERE status = 'PENDING' ORDER BY id LIMIT 100", (rs, i) -> Row.of(rs));
    }

    private void update(Row r) {
        jdbc.update("UPDATE " + TABLE + " SET status = ?, attempts = ?, last_error = ?, receipt_no = ?, updated_at = ? WHERE id = ?",
                r.status, r.attempts, r.lastError, r.receiptNo, Timestamp.valueOf(LocalDateTime.now()), r.id);
    }

    private String write(PaymentRecordRequest r) {
        try { return json.writeValueAsString(r); }
        catch (Exception e) { throw new IllegalStateException("Could not record the ledger payment", e); }
    }

    PaymentRecordRequest read(String payload) {
        try { return json.readValue(payload, PaymentRecordRequest.class); }
        catch (Exception e) { throw new IllegalStateException("Unreadable ledger payment payload", e); }
    }

    /** One {@code ledger_payment_outbox} row, as the shared relay sees it. */
    static final class Row implements OutboxEntry {
        Long id, organizationId, userId;
        String clientRef, payload, status, lastError, receiptNo;
        Integer attempts;

        static Row of(java.sql.ResultSet rs) throws java.sql.SQLException {
            Row r = new Row();
            r.id = rs.getLong(1);
            r.organizationId = (Long) rs.getObject(2, Long.class);
            r.userId = (Long) rs.getObject(3, Long.class);
            r.clientRef = rs.getString(4);
            r.payload = rs.getString(5);
            r.status = rs.getString(6);
            r.attempts = rs.getInt(7);
            r.lastError = rs.getString(8);
            r.receiptNo = rs.getString(9);
            return r;
        }

        @Override public Long getId() { return id; }
        @Override public String getStatus() { return status; }
        @Override public void setStatus(String s) { this.status = s; }
        @Override public Integer getAttempts() { return attempts; }
        @Override public void setAttempts(Integer a) { this.attempts = a; }
        @Override public void setLastError(String e) { this.lastError = e == null ? null : (e.length() > 500 ? e.substring(0, 500) : e); }
        @Override public Long getUserId() { return userId; }
        @Override public Long getOrganizationId() { return organizationId; }
        @Override public void setUpdatedAt(LocalDateTime t) { /* stamped by update() */ }
    }
}

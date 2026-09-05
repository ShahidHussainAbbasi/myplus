package com.myplus.common.outbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D-6 — how many events have not been delivered, why, and how to send them again.
 *
 * <h3>The defect this exists for</h3>
 * {@link OutboxRelay} dead-letters a row at 20 attempts and nothing looks at it again: no count anywhere, no
 * re-send, no screen, and {@code last_error} readable only by someone with database access. On 16 August that
 * silently swallowed <b>57 general-ledger events worth PKR 137,510</b> — every education fee posting ever
 * emitted — and they were found weeks later by a person reading the database directly. The failures are one
 * problem; that a person had to go looking is the one this class closes.
 *
 * <h3>Why raw SQL over a registered table name, rather than a repository per outbox</h3>
 * All seven outboxes share the delivery columns (verified against {@code information_schema}, not assumed),
 * so one implementation serves every one of them. A repository per table would be seven near-identical
 * interfaces, and the eighth outbox would silently not be covered.
 *
 * <h3>⚠ The table name is validated against the registry, never interpolated as given</h3>
 * It arrives in a request. {@link #assertKnown} checks it against {@link OutboxHealthRegistry} first, so the
 * only names that ever reach SQL are ones the service's own code declared. Without that this class is an
 * arbitrary {@code UPDATE} endpoint.
 */
public class OutboxHealthService {

    /** Longest a stored error is grouped by. A stack trace must not flood the payload. */
    private static final int REASON_LEN = 200;

    /** Bound on a single re-drive, so "all" cannot mean an unbounded write on a runaway table. */
    private static final int MAX_REDRIVE = 500;

    private final JdbcTemplate jdbc;
    private final OutboxHealthRegistry registry;

    public OutboxHealthService(DataSource dataSource, OutboxHealthRegistry registry) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.registry = registry;
    }

    /**
     * Every outbox this service owns: what is waiting, what has been given up on, and why.
     *
     * <p>{@code reasons} is the field that turns a number into a diagnosis. Fifty-seven failures sharing two
     * distinct messages is exactly the shape where one fix clears everything, and a bare count of 57 says
     * none of that — it sends an operator to the database, where this whole problem was found in the first
     * place.
     */
    public Map<String, Object> health() {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (String table : registry.outboxTables()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("table", table);
            t.put("pending", count(table, "PENDING"));
            t.put("failed", count(table, "FAILED"));
            t.put("posted", count(table, "POSTED"));
            t.put("oldestFailed", oldest(table, "FAILED"));
            t.put("reasons", reasons(table));
            tables.add(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        return out;
    }

    /**
     * Put failed rows back on the queue.
     *
     * <p>Resets to exactly the state {@link OutboxRelay} already drives — {@code PENDING} with the attempt
     * counter cleared — rather than teaching the relay a third state. Retry policy stays in one place; this
     * only moves a row back into it.
     *
     * @param ids specific rows, or null with {@code all} to take every failed row in the table
     * @return how many rows were actually reset
     */
    public int redrive(String table, List<Long> ids, boolean all) {
        assertKnown(table);
        if ((ids == null || ids.isEmpty()) && !all) {
            /*
             * ⚠ NO DEFAULT, DELIBERATELY.
             *
             * The difference between replaying ONE event to read its exception and replaying fifty-six into a
             * live ledger is one word. If an unscoped call meant "everything", the safe case and the
             * irreversible one would look identical at the call site.
             */
            throw new IllegalArgumentException("Name the rows to re-send, or say all.");
        }

        if (ids != null && !ids.isEmpty()) {
            if (ids.size() > MAX_REDRIVE)
                throw new IllegalArgumentException("At most " + MAX_REDRIVE + " rows can be re-sent at once.");
            String marks = String.join(",", ids.stream().map(i -> "?").toList());
            return jdbc.update(
                    "update `" + table + "` set status='PENDING', attempts=0, last_error=null, updated_at=now()"
                            + " where status='FAILED' and id in (" + marks + ")",
                    ids.toArray());
        }
        return jdbc.update(
                "update `" + table + "` set status='PENDING', attempts=0, last_error=null, updated_at=now()"
                        + " where status='FAILED' limit " + MAX_REDRIVE);
    }

    /**
     * Test fixture: make one row that is genuinely FAILED, so a gate can prove the round trip.
     *
     * <h3>⚠ Why this exists in the product rather than in the spec</h3>
     * The only failed rows on any real system are <b>real lost events</b> — 56 of them are education fee
     * postings worth PKR 137,510. A gate that re-drove those would have done the operator's job without the
     * operator, into a live ledger, with nobody deciding it. So the gate seeds its own subject, and seeding
     * needs a writer inside the service that owns the table.
     *
     * <p>Guarded the way {@code SetupDataLoader} guards its dev fixtures: a property that defaults on for
     * development, <b>plus</b> an independent hard block on the prod profile, so flipping the property alone
     * cannot arm it in production. The caller is already {@code ROLE_ADMIN}.
     */
    public long seedFailed(String table, String reason) {
        assertKnown(table);
        /*
         * ⚠ AUDIT OUTBOXES ONLY, and the limit is real rather than cautious.
         *
         * The seven outboxes share their DELIVERY columns but not their payload ones: audit_outbox requires
         * `action`, gl_outbox requires `event_type`, notify_outbox its own. There is no set of columns that
         * satisfies all three, so a "generic" insert would compile and then fail at runtime on whichever
         * table nobody tried.
         *
         * Refused explicitly instead. A gate needs one row it may safely re-drive, and an audit fixture row
         * is exactly that — no money moves when it is delivered.
         */
        if (!table.toLowerCase().contains("audit"))
            throw new IllegalArgumentException(
                    "Only an audit outbox can be seeded: the others carry payload columns this cannot fill.");
        /*
         * ⚠ STAMP A REAL IDENTITY, or the row can never be delivered.
         *
         * The first cut inserted a null organization_id and user_id. The re-drive then worked perfectly — the
         * row moved FAILED → PENDING — and the relay could still never deliver it: it impersonates the row's
         * tenant with runAs(userId, organizationId), so a null pair sends no X-User-Id and no X-Org-Id,
         * audit-service authenticates nobody, and every attempt comes back "403 : [no body]". The row sat at
         * PENDING for ever and the gate reported a broken re-drive, which is the one thing that was working.
         *
         * The caller is the operator, and their identity is exactly the right one for a fixture: it is a real
         * tenant that really can receive an audit event.
         */
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        Long user = com.myplus.common.security.CurrentUser.userId();
        jdbc.update(
                "insert into `" + table + "` (action, entity_type, entity_ref, details, event_key,"
                        + " occurred_at, status, attempts, last_error, organization_id, user_id,"
                        + " created_at, updated_at)"
                        + " values (?,?,?,?,?, now(), 'FAILED', 20, ?, ?, ?, now(), now())",
                "GATE_FIXTURE", "FIXTURE", "d6", reason, java.util.UUID.randomUUID().toString(),
                "seeded by the D-6 gate; safe to re-drive", org, user);
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        return id == null ? 0L : id;
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The allow-list check. Everything that builds SQL goes through here first.
     *
     * <p>Compared case-insensitively against the service's own registry, so the only strings that can reach a
     * statement are ones a developer wrote in that service. A request cannot introduce a table name.
     */
    private void assertKnown(String table) {
        if (table == null || registry.outboxTables().stream().noneMatch(t -> t.equalsIgnoreCase(table)))
            throw new IllegalArgumentException("Not an outbox this service owns: " + table);
    }

    private long count(String table, String status) {
        assertKnown(table);
        Long n = jdbc.queryForObject("select count(*) from `" + table + "` where status = ?", Long.class, status);
        return n == null ? 0L : n;
    }

    private String oldest(String table, String status) {
        assertKnown(table);
        List<String> r = jdbc.queryForList(
                "select min(created_at) from `" + table + "` where status = ?", String.class, status);
        return r.isEmpty() ? null : r.get(0);
    }

    private List<Map<String, Object>> reasons(String table) {
        assertKnown(table);
        return jdbc.query(
                "select count(*) n, left(coalesce(last_error,''), " + REASON_LEN + ") msg"
                        + " from `" + table + "` where status='FAILED'"
                        + " group by msg order by n desc limit 10",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("count", rs.getLong("n"));
                    m.put("message", rs.getString("msg"));
                    return m;
                });
    }
}

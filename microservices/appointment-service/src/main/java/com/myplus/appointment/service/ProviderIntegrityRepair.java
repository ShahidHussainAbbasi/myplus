package com.myplus.appointment.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HMS S2 — the automatic clean-up of doctors that broke tenancy. Runs at every start; idempotent; no manual step
 * (standing rule: a repair is a job that runs on deploy, never a script someone has to remember).
 *
 * <h3>What it repairs</h3>
 * A provider whose venue belongs to ANOTHER organisation. {@code DoctorService.create} used to save any venue id it
 * was sent (fixed in S2; found by hms-s2-token-queue.cy.js S2-11), so such rows can exist from before the fix. Each
 * is COPIED to {@code provider_quarantine} (V6) with when and why, and only then removed from {@code provider}.
 *
 * <h3>What it refuses to decide</h3>
 * A mismatched provider that already has bookings or slots is left exactly where it is and reported at WARN with its
 * ids: which organisation those bookings really belong to cannot be proven from the data, and guessing would move
 * someone's appointments. Unprovable → reported, never guessed.
 *
 * <h3>Never stops the service</h3>
 * Any failure is logged and the service starts anyway: a clean-up must not take the booking screens down.
 */
@Component
public class ProviderIntegrityRepair {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderIntegrityRepair.class);

    static final String FIND = "SELECT p.id, p.organization_id AS provider_org, v.organization_id AS venue_org, "
            + "(EXISTS (SELECT 1 FROM booking b WHERE b.provider_id = p.id) "
            + " OR EXISTS (SELECT 1 FROM slot s WHERE s.provider_id = p.id)) AS referenced "
            + "FROM provider p JOIN venue v ON v.id = p.venue_id "
            + "WHERE v.organization_id <> p.organization_id";

    static final String COLUMNS = "SELECT p.column_name FROM information_schema.columns p "
            + "JOIN information_schema.columns q ON q.table_schema = p.table_schema AND q.column_name = p.column_name "
            + "AND q.table_name = 'provider_quarantine' "
            + "WHERE p.table_schema = DATABASE() AND p.table_name = 'provider' ORDER BY p.ordinal_position";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ProviderIntegrityRepair(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** What one run did: ids moved to quarantine, and ids left in place because they have bookings or slots. */
    public record Result(List<Long> quarantined, List<Long> leftReferenced) {}

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            Result r = repair();
            if (!r.quarantined().isEmpty()) {
                LOG.warn("Provider integrity repair: moved {} doctor(s) attached to another organisation's venue to "
                        + "provider_quarantine: ids {}", r.quarantined().size(), r.quarantined());
            }
            if (!r.leftReferenced().isEmpty()) {
                LOG.warn("Provider integrity repair: {} doctor(s) are attached to another organisation's venue but have "
                        + "bookings or slots, so they were NOT moved — review by hand: ids {}",
                        r.leftReferenced().size(), r.leftReferenced());
            }
            if (r.quarantined().isEmpty() && r.leftReferenced().isEmpty()) {
                LOG.info("Provider integrity repair: every doctor belongs to its own organisation's venue.");
            }
        } catch (RuntimeException failed) {
            LOG.error("Provider integrity repair failed; the service starts anyway", failed);
        }
    }

    /** One pass, in one transaction: copy, then remove — a failure half-way leaves nothing changed. */
    public Result repair() {
        return tx.execute(status -> {
            List<Map<String, Object>> found = jdbc.queryForList(FIND);
            List<Long> movable = new ArrayList<>();
            List<Long> referenced = new ArrayList<>();
            for (Map<String, Object> row : found) {
                long id = ((Number) row.get("id")).longValue();
                if (truthy(row.get("referenced"))) referenced.add(id);
                else movable.add(id);
            }
            if (movable.isEmpty()) return new Result(List.of(), referenced);

            List<String> cols = jdbc.queryForList(COLUMNS, String.class);
            if (cols.isEmpty() || !cols.contains("id")) {
                throw new IllegalStateException("provider_quarantine is missing or has no id column (V6 not applied?)");
            }
            String colList = cols.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
            String ids = movable.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbc.update("INSERT INTO provider_quarantine (" + colList + ", quarantined_at, quarantine_reason) "
                    + "SELECT " + colList + ", NOW(), 'venue belongs to another organisation' FROM provider "
                    + "WHERE id IN (" + ids + ") AND id NOT IN (SELECT id FROM provider_quarantine)");
            jdbc.update("DELETE FROM provider WHERE id IN (" + ids + ") AND id IN (SELECT id FROM provider_quarantine)");
            return new Result(movable, referenced);
        });
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return v != null && "1".equals(v.toString());
    }
}

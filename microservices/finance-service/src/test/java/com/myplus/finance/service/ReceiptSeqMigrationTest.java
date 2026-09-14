package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DOC-INT B — V7 seeds each receipt counter from the numbers ALREADY ISSUED, and leaves history alone.
 *
 * <h3>Why this test drives Flyway by hand</h3>
 * V7's only way to do harm is its seeding: a counter that starts below a number already on paper re-issues it,
 * and the legacy row carries no receipt_seq, so the new UNIQUE could not catch the collision. Seeding can only
 * be tested with legacy rows present BEFORE V7 runs — so this migrates to V6, writes the awkward history the
 * real ledger has (a duplicated number, a disbursement carrying the old RCPT- prefix), and only then runs V7.
 *
 * <p>Real MySQL: the migration uses PREPARE / information_schema / REGEXP, none of which H2 runs faithfully.
 * Skips without Docker — read the SKIP count.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReceiptSeqMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private Flyway flyway(String target) {
        var cfg = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) cfg.target(target);
        return cfg.load();
    }

    private static void pay(JdbcTemplate jdbc, long org, String direction, String receiptNo) {
        jdbc.update("INSERT INTO payments (direction, party_type, amount, receipt_no, organization_id) "
                + "VALUES (?, 'CUSTOMER', 10.00, ?, ?)", direction, receiptNo, org);
    }

    private static Long counter(JdbcTemplate jdbc, long org, String docType) {
        List<Long> v = jdbc.queryForList(
                "SELECT next_val FROM org_document_seq WHERE organization_id = ? AND doc_type = ?",
                Long.class, org, docType);
        return v.isEmpty() ? null : v.get(0);
    }

    @Test
    void counters_start_at_the_highest_number_issued_and_history_is_untouched() {
        flyway("6").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));

        // org 1 — the shape the real ledger has
        pay(jdbc, 1, "RECEIPT", "RCPT-000007");
        pay(jdbc, 1, "RECEIPT", "RCPT-000007");        // the count+1 duplicate
        pay(jdbc, 1, "DISBURSEMENT", "RCPT-000009");   // legacy: a disbursement from before the PV- prefix
        pay(jdbc, 1, "DISBURSEMENT", "PV-000003");
        // org 2 — receipts only
        pay(jdbc, 2, "RECEIPT", "RCPT-000002");

        flyway(null).migrate();

        // RCPT- numbers share one string space whatever their direction, so the RECEIPT counter must clear 9.
        assertThat(counter(jdbc, 1, "RECEIPT")).isEqualTo(9L);
        assertThat(counter(jdbc, 1, "DISBURSEMENT")).isEqualTo(3L);
        assertThat(counter(jdbc, 2, "RECEIPT")).isEqualTo(2L);
        assertThat(counter(jdbc, 2, "DISBURSEMENT")).as("no PV- history, no counter yet").isNull();

        // History is on paper: nothing renumbered, nothing removed, nothing stamped.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE receipt_no = 'RCPT-000007'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE receipt_seq IS NOT NULL", Integer.class)).isZero();

        // And the new arbiter exists, per org AND direction.
        assertThat(jdbc.queryForObject(
                "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' "
                        + "AND INDEX_NAME = 'uq_pay_org_dir_seq' AND NON_UNIQUE = 0", String.class))
                .isEqualTo("organization_id,direction,receipt_seq");
    }
}

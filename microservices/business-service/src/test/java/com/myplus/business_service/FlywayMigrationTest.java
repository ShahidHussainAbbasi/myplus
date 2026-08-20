package com.myplus.business_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.business_service.entity.MovementType;
import com.myplus.business_service.entity.PaymentMethod;
import com.myplus.business_service.entity.ShiftStatus;
import com.myplus.business_service.entity.TaxMode;

/**
 * Slice DB-D2 — <b>business-service's 41 migrations, executed by {@code mvn test} against an empty database.</b>
 * Design: {@code microservices/docs/slices/db-D2-business-flyway-test.md}
 *
 * <h3>Why this exists, concretely</h3>
 * Standard D2 requires it and this service did not meet it: of 18 test classes only three touch a database, and
 * all three run {@code flyway.enabled=false} with {@code ddl-auto=create-drop} — i.e. against a schema Hibernate
 * invents. So every migration was unexercised, and slice I1 paid for it on 2026-08-19:
 *
 * <p><b>V41 shipped an index MySQL refuses to create.</b> {@code (organization_id, contact)} is 1028 bytes and
 * {@code customer} is MyISAM, whose key limit is 1000. What a human saw was a Cypress failure reading
 * <i>"downstream token still valid"</i>, caused by the monolith answering {@code 200 {"status":"ERROR"}},
 * caused by business-service crash-looping, caused by {@code ERROR 1071}. Four layers, in a browser. This test
 * would have said <i>"Script V41 failed: ERROR 1071"</i> in the build.
 *
 * <h3>The fresh-install path is the one nobody runs</h3>
 * {@code baseline-on-migrate: true} fires only against a NON-EMPTY schema with no history table — the adoption
 * case, where V1 is marked applied and skipped. Against an empty schema Flyway baselines nothing and runs
 * V1 → V41. Every existing database took the first path; <b>a customer's first install takes the second</b>,
 * and until now nothing had ever executed it.
 *
 * <h3>Honest framing: this is a tripwire, not a repair</h3>
 * All four enum columns match their Java enums today and no column drift exists — measured, not assumed. The
 * value is entirely in what it stops arriving next.
 *
 * <p>Real MySQL, because the migrations use MySQL-specific {@code PREPARE} / {@code information_schema} guards
 * and because the MyISAM key limit does not exist on H2 at all. Skips without Docker.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    /** A dedicated container: these assertions are about a VIRGIN database, not one another test has touched. */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.flyway.enabled", () -> "true");         // the entire point of this test
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private boolean hasTable(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, table);
        return n != null && n > 0;
    }

    private String columnType(String table, String column) {
        List<String> types = jdbc.queryForList(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
        return types.isEmpty() ? null : types.get(0);
    }

    /** Assert a MySQL ENUM column carries every constant of the Java enum mapped onto it. */
    private void assertEnumCovers(String table, String column, Enum<?>[] constants) {
        String type = columnType(table, column);
        assertThat(type).as("%s.%s should exist and be a MySQL ENUM", table, column)
                .isNotNull().startsWith("enum(");
        for (Enum<?> c : constants) {
            assertThat(type).as(
                    "%s.%s must include '%s'. A Java constant with no ALTER … MODIFY behind it compiles "
                            + "fine, passes every unit test against Hibernate's invented schema, and then "
                            + "dies on the first insert with \"Data truncated for column '%s'\"",
                    table, column, c.name(), column)
                    .contains("'" + c.name() + "'");
        }
    }

    // ── A1 · the migrations apply at all ────────────────────────────────────────────────────────────────────

    /**
     * Booting is most of the assertion. {@code ddl-auto=validate} means Hibernate has already agreed that what
     * Flyway built matches all 25 entities — so a migration that fails, or a column a migration forgot, never
     * reaches this method body.
     *
     * <p>Deliberately NOT asserting an exact version list (marketplace does, with 17). At 41 and growing, a
     * hardcoded list is a second copy of the migration directory that must be edited by whoever adds a
     * migration — i.e. it fails for the correct change as readily as the incorrect one. What matters is that
     * the chain is unbroken and complete, which is what these two assertions say.
     */
    @Test
    void every_migration_applies_cleanly_to_an_empty_database() {
        List<Map<String, Object>> failed = jdbc.queryForList(
                "SELECT version, description FROM flyway_schema_history WHERE success = 0");
        assertThat(failed).as("a failed migration blocks startup for every fresh deploy").isEmpty();

        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL",
                Integer.class);
        assertThat(applied).as("V1's 67-table baseline through to the newest migration")
                .isNotNull().isGreaterThanOrEqualTo(41);
    }

    // ── A2 · every MySQL ENUM covers its Java enum ──────────────────────────────────────────────────────────

    /**
     * The failure mode that takes the service down in production and is invisible everywhere else.
     *
     * <p>Derived from the Java enums by reflection rather than a hardcoded list, deliberately: a hardcoded
     * list would need editing at exactly the moment someone forgets the {@code ALTER}, so it would pass in
     * precisely the case it exists to catch.
     */
    @Test
    void every_mysql_enum_covers_every_java_constant() {
        assertEnumCovers("payment", "method", PaymentMethod.values());
        assertEnumCovers("cash_movement", "type", MovementType.values());
        assertEnumCovers("cashier_shift", "status", ShiftStatus.values());
        assertEnumCovers("tax_setting", "tax_mode", TaxMode.values());
    }

    // ── A3 · the MyISAM key limit, generalised ──────────────────────────────────────────────────────────────

    /**
     * <b>No index on a MyISAM table may exceed 1000 bytes.</b> Earned directly by V41 (slice I1), and
     * generalised on purpose: this guards every future index on any of the 67 MyISAM tables, not only the one
     * that bit.
     *
     * <p>Why it is easy to get wrong: InnoDB allows 3072 bytes with DYNAMIC row format, so the arithmetic that
     * fails here succeeds on most tables in most services. And it is a byte limit, not a character one — a
     * {@code varchar(255)} costs 1020 bytes in utf8mb4 and blows the budget on its own, while the same column
     * would look harmless counted as 255.
     *
     * <p>{@code SUB_PART} is honoured, which is what makes a prefix index — {@code contact(64)} — the fix
     * rather than a workaround.
     *
     * <p><b>The byte count is an ESTIMATE, and it errs high on purpose.</b> Character columns are exact
     * ({@code CHARACTER_OCTET_LENGTH}, or bytes-per-char × {@code SUB_PART} for a prefix), but numerics have
     * no storage width in {@code information_schema}, so {@code NUMERIC_PRECISION} stands in — which counts
     * DIGITS, not bytes. A {@code bigint} therefore scores 19 where it occupies 8. Verified against the live
     * schema: {@code idx_customer_org_contact} computes to 275 (19 + 64×4) against a true 264.
     *
     * <p>Erring high is the correct direction for a guard — it can warn slightly early, but it cannot miss a
     * real violation. The overstatement is ~11 bytes per numeric column against a 1000-byte budget, so it
     * only ever matters for an index already within a hair of the limit, which is one that should be
     * reconsidered anyway.
     */
    @Test
    void no_myisam_index_exceeds_the_1000_byte_key_limit() {
        List<Map<String, Object>> oversized = jdbc.queryForList(
                "SELECT s.TABLE_NAME, s.INDEX_NAME, "
              + "       SUM(COALESCE(s.SUB_PART, 1) * "
              + "           CASE WHEN c.CHARACTER_OCTET_LENGTH IS NULL "
              + "                THEN COALESCE(c.NUMERIC_PRECISION, 8) "
              + "                ELSE CASE WHEN s.SUB_PART IS NULL "
              + "                          THEN c.CHARACTER_OCTET_LENGTH "
              + "                          ELSE c.CHARACTER_OCTET_LENGTH / c.CHARACTER_MAXIMUM_LENGTH END "
              + "           END) AS key_bytes "
              + "  FROM information_schema.STATISTICS s "
              + "  JOIN information_schema.TABLES t "
              + "    ON t.TABLE_SCHEMA = s.TABLE_SCHEMA AND t.TABLE_NAME = s.TABLE_NAME "
              + "  JOIN information_schema.COLUMNS c "
              + "    ON c.TABLE_SCHEMA = s.TABLE_SCHEMA AND c.TABLE_NAME = s.TABLE_NAME "
              + "   AND c.COLUMN_NAME = s.COLUMN_NAME "
              + " WHERE s.TABLE_SCHEMA = DATABASE() AND t.ENGINE = 'MyISAM' "
              + " GROUP BY s.TABLE_NAME, s.INDEX_NAME "
              + "HAVING key_bytes > 1000");

        assertThat(oversized).as(
                "MyISAM caps a key at 1000 BYTES (InnoDB allows 3072, which is why this is easy to miss). "
                        + "A varchar(255) is 1020 bytes in utf8mb4 on its own. Use a prefix — contact(64) — "
                        + "as V41 does. Offending indexes: %s", oversized)
                .isEmpty();
    }

    // ── A4 · V41's prefix survives ──────────────────────────────────────────────────────────────────────────

    /**
     * The import's batched duplicate check (slice I1) needs this index, and it needs the PREFIX.
     *
     * <p>A bare {@code (64)} in a migration looks like an accident to a later reader, and removing it produces
     * a service that will not start. Asserting {@code SUB_PART} makes that a red build instead.
     */
    @Test
    void the_customer_contact_index_keeps_its_prefix() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT COLUMN_NAME, SEQ_IN_INDEX, SUB_PART FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' "
                        + "AND INDEX_NAME = 'idx_customer_org_contact' ORDER BY SEQ_IN_INDEX");

        assertThat(cols).as("V41 — the index behind the CSV import's duplicate check").hasSize(2);
        assertThat(cols.get(0).get("COLUMN_NAME")).isEqualTo("organization_id");
        assertThat(cols.get(1).get("COLUMN_NAME")).isEqualTo("contact");
        assertThat(((Number) cols.get(1).get("SUB_PART")).intValue())
                .as("the prefix is load-bearing: the full column would be 1028 bytes against a 1000-byte limit")
                .isEqualTo(64);
    }

    // ── A5 · the drops stay dropped ─────────────────────────────────────────────────────────────────────────

    /**
     * V7 and V8 removed the local stock table and the Item→Product bridge when POS converged onto the catalog
     * master. Asserting their ABSENCE proves the drop chain ran on a fresh database, and catches a future
     * entity quietly resurrecting a table the convergence deleted.
     */
    @Test
    void the_tables_the_item_product_convergence_removed_are_gone() {
        assertThat(hasTable("item")).as("V8 — POS sells catalog Products now").isFalse();
        assertThat(hasTable("item_catalog_map")).as("V8 — the itemId↔productId bridge").isFalse();
        assertThat(hasTable("stock")).as("V7 — stock belongs to inventory-service").isFalse();
    }

    /** Conversely: the tables the service actually runs on must be there after a migrate-from-nothing. */
    @Test
    void the_core_trade_tables_exist_after_a_migrate_from_nothing() {
        for (String t : List.of("customer", "customer_history", "sell", "purchase", "vender",
                                "store", "sales_quote", "payment", "gl_outbox", "org_setting")) {
            assertThat(hasTable(t)).as("%s must exist on a customer's FIRST install, not just on ours", t)
                    .isTrue();
        }
    }
}

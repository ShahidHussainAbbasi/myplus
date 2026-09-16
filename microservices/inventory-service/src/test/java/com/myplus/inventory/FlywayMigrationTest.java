package com.myplus.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * inventory-service's migrations, executed against an EMPTY database by {@code mvn test} (standard D2).
 *
 * <p>inventory had no test of this shape — business, marketplace, pharma and catalog do. It was added with BLK-5
 * (V11), and it answers a second question found in the same review: the dev database's stock quantities are
 * decimal(38,2) although V8 says DECIMAL(19,4). Asserting the Flyway-built schema says whether a FRESH deploy gets
 * V8's type — i.e. whether the drift is the dev database's or the migrations'.
 *
 * <p>{@code ddl-auto} is {@code none}, not {@code validate}: whether every inventory entity validates against the
 * migrations is the pending "flip dev update → validate" work, and a failure there must not be mistaken for a BLK-5
 * one. The columns this slice adds are asserted by TYPE below instead — the entity ↔ column contract that matters.
 *
 * <p>⚠ On this machine Testcontainers needs {@code -Dapi.version=1.41} (pinned in the parent pom). If the SKIPPED
 * count is ever non-zero, these assertions are not running.
 */
@SpringBootTest
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
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");  // Flyway alone builds the schema
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;

    private String columnType(String table, String column) {
        List<String> types = jdbc.queryForList(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
        return types.isEmpty() ? null : types.get(0);
    }

    @Test
    @DisplayName("every migration applies to an empty database")
    void every_migration_applies() {
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertThat(failed).as("a migration failed").isZero();

        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(applied).as("V1..V12 applied").isGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("⭐ COGS-1: a batch records what it was RECEIVED with, as DECIMAL(19,4) like its quantity (V12)")
    void cogs1_received_quantity_column_exists() {
        // The entity maps BigDecimal(19,4); a mismatch is a service that will not start under ddl-auto=validate.
        assertThat(columnType("stock_entries", "received_quantity")).isEqualTo("decimal(19,4)");
    }

    @Test
    @DisplayName("⭐ no two migrations share a version — a duplicate is SILENTLY skipped")
    void versions_are_unique() {
        // Asserted against the FILES, not the history: a duplicate makes the history unreliable (catalog's U1 trap).
        java.io.File dir = new java.io.File("src/main/resources/db/migration");
        java.util.Map<String, String> byVersion = new java.util.HashMap<>();
        java.util.List<String> clashes = new java.util.ArrayList<>();
        for (String name : java.util.Objects.requireNonNull(dir.list())) {
            if (!name.startsWith("V") || !name.contains("__")) continue;
            String version = name.substring(1, name.indexOf("__"));
            String previous = byVersion.put(version, name);
            if (previous != null) clashes.add("V" + version + ": " + previous + " and " + name);
        }
        assertThat(clashes).as("two migrations claiming one version — one of them will never run").isEmpty();
    }

    @Test
    @DisplayName("⭐ BLK-5: a stock correction can say which shop, and carry a key")
    void blk5_columns_exist() {
        // Types, because the entity maps Long organizationId and a length-191 String — a mismatch is a service that
        // does not start once inventory runs ddl-auto=validate.
        assertThat(columnType("stock_adjustments", "organization_id")).isEqualTo("bigint");
        assertThat(columnType("stock_adjustments", "idempotency_key")).isEqualTo("varchar(191)");
    }

    @Test
    @DisplayName("⭐⭐ BLK-5: the key is UNIQUE per shop — the arbiter between racers")
    void blk5_unique_index_is_per_tenant() {
        List<String> cols = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'stock_adjustments' AND INDEX_NAME = 'uq_adj_org_idem' AND NON_UNIQUE = 0 "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertThat(cols).as("UNIQUE (organization_id, idempotency_key), in that order")
                .containsExactly("organization_id", "idempotency_key");
    }

    @Test
    @DisplayName("stock quantities are DECIMAL(19,4) on a Flyway-built database (V8)")
    void stock_quantities_keep_four_decimal_places() {
        // A third of a pack is 0.3333. The dev database holds these as decimal(38,2) and already shows 9.67 where
        // 9.6667 was meant. This pins what a FRESH deploy gets, so that drift is known to be the dev database's.
        assertThat(columnType("stock_levels", "current_stock")).isEqualTo("decimal(19,4)");
        assertThat(columnType("stock_entries", "quantity")).isEqualTo("decimal(19,4)");
        assertThat(columnType("stock_adjustments", "quantity")).isEqualTo("decimal(19,4)");
        assertThat(columnType("reservation_picks", "quantity")).isEqualTo("decimal(19,4)");
    }
}

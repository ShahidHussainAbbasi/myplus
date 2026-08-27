package com.myplus.catalog;

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
 * catalog-service's migrations, executed against an EMPTY database by {@code mvn test}.
 *
 * <h3>Why this exists — a migration recorded as applied that never ran</h3>
 * U1's pack-size migration was written as {@code V10}. A {@code V10} already existed
 * ({@code V10__products_org_name_index.sql}), so Flyway found version 10 in its history, considered it done
 * and <b>never opened the file</b> — while reporting {@code success = 1}. Nothing errored. The columns simply
 * were not there, and because the history says applied, <b>it would never have run again</b>.
 *
 * <p>The mistake behind it was reading the directory with {@code ls | tail -3}: that sorts LEXICALLY, so V7,
 * V8, V9 look like the end of the list and V10 sorts above them.
 *
 * <p>business-service, marketplace-service and pharma-service each have a test of this shape. catalog-service
 * did not, which is precisely why the gap went unnoticed here and nowhere else. Standard D2.
 *
 * <p>⚠ On this machine Testcontainers needs {@code -Dapi.version=1.41}; it is pinned in the parent pom, so a
 * plain {@code mvn test} runs these. If the SKIPPED count is ever non-zero, these assertions are not running.
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
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
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
        assertThat(applied).as("migrations applied").isGreaterThanOrEqualTo(11);
    }

    @Test
    @DisplayName("⭐ no two migrations share a version — a duplicate is SILENTLY skipped")
    void versions_are_unique() {
        /*
         * THE CASE THIS CLASS WAS WRITTEN FOR.
         *
         * A duplicate version does not fail: Flyway applies whichever it resolves first, records success, and
         * the other file is never opened. The schema is missing whatever that file contained, and the history
         * says everything is fine — so nothing will ever run it.
         *
         * Asserted against the FILES, not the history, because the history is exactly what a duplicate makes
         * unreliable: it will show one row for version 10 whether one file exists or two.
         */
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
    @DisplayName("⭐ U1's pack columns are actually on the table")
    void pack_columns_exist() {
        // Not "the migration ran" — the migration DID report success while doing nothing. The property is
        // that the columns EXIST, which is the only thing the sale path can rely on.
        assertThat(columnType("products", "pack_size")).as("pack_size").isEqualTo("int");
        assertThat(columnType("products", "loose_unit")).as("loose_unit").isEqualTo("varchar(32)");
        assertThat(columnType("products", "loose_unit_plural")).as("loose_unit_plural").isEqualTo("varchar(32)");
        assertThat(columnType("products", "allow_loose")).as("allow_loose").isEqualTo("tinyint(1)");
        assertThat(columnType("products", "default_sell_unit")).as("default_sell_unit").isEqualTo("varchar(8)");
    }

    @Test
    @DisplayName("a pack rule change can be attributed — who and when")
    void pack_audit_columns_exist() {
        // The standards require pricing controls to be auditable, and `products` recorded created_by only:
        // "who allowed this to be split?" had no answer at all.
        assertThat(columnType("products", "pack_changed_by")).as("pack_changed_by").isEqualTo("bigint");
        assertThat(columnType("products", "pack_changed_at")).as("pack_changed_at").isEqualTo("datetime");
    }

    @Test
    @DisplayName("nothing becomes divisible just because the columns appeared")
    void defaults_leave_every_product_as_it_was() {
        // A default is not a decision. On a virgin database there are no products, so this asserts the
        // COLUMN DEFAULTS rather than any row — which is what an existing tenant's rows will inherit.
        String allowLooseDefault = jdbc.queryForObject(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'products' AND COLUMN_NAME = 'allow_loose'",
                String.class);
        assertThat(allowLooseDefault).as("loose selling is off unless a shop asks").isEqualTo("0");

        String unitDefault = jdbc.queryForObject(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'products' AND COLUMN_NAME = 'default_sell_unit'",
                String.class);
        assertThat(unitDefault).as("lines start in PACK").isEqualTo("PACK");
    }
}

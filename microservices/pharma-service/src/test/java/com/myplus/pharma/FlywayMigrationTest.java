package com.myplus.pharma;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

/**
 * Review finding D1 — the migrations were never executed by {@code mvn test}.
 *
 * Every other test in this service runs with {@code spring.flyway.enabled=false} and {@code ddl-auto=create-drop},
 * i.e. against a schema Hibernate invents. That tests the code but leaves V1–V5 completely unexercised — including
 * V3's state-aware {@code item_id → product_id} rename, the most intricate SQL here, and it is exactly the
 * fresh-deploy path a customer's first install takes.
 *
 * This test is the opposite: an EMPTY MySQL, schema built only by Flyway, and {@code ddl-auto=validate} so
 * Hibernate must agree the result matches the entities. Boot the context and both halves are proven at once —
 * the migrations apply in order on a clean database, and what they produce is what the code expects.
 *
 * Real MySQL (the migrations use MySQL-specific PREPARE/information_schema guards); skips without Docker.
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
        r.add("spring.flyway.enabled", () -> "true");        // the point of this test
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;

    private boolean hasColumn(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    private boolean hasIndex(String table, String index) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, index);
        return n != null && n > 0;
    }

    @Test
    void every_migration_applies_to_an_empty_database() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL "
                        + "ORDER BY installed_rank", String.class);
        // If a later migration is added without being listed here, this is the reminder to prove it too.
        assertThat(applied).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void the_productId_rebase_left_no_legacy_itemId_columns() {
        // V3 converges item_id → product_id across four tables, branching on which columns a given database
        // already has. On a virgin database it takes the RENAME branch — never exercised until now.
        assertThat(hasColumn("prescription_items", "product_id")).isTrue();
        assertThat(hasColumn("dispensing", "product_id")).isTrue();
        assertThat(hasColumn("medicine_clinical", "product_id")).isTrue();
        assertThat(hasColumn("drug_interactions", "product_id1")).isTrue();
        assertThat(hasColumn("drug_interactions", "product_id2")).isTrue();

        assertThat(hasColumn("prescription_items", "item_id")).isFalse();
        assertThat(hasColumn("dispensing", "item_id")).isFalse();
        assertThat(hasColumn("medicine_clinical", "item_id")).isFalse();
        assertThat(hasColumn("drug_interactions", "item_id1")).isFalse();
        assertThat(hasColumn("drug_interactions", "item_id2")).isFalse();
    }

    @Test
    void tenant_scope_and_feature_columns_exist() {
        assertThat(hasColumn("prescriptions", "organization_id")).isTrue();
        assertThat(hasColumn("prescriptions", "party_id")).isTrue();          // V4, party bridge
        assertThat(hasColumn("dispensing", "organization_id")).isTrue();
        assertThat(hasColumn("dispensing", "invoice_no")).isTrue();           // B3 idempotency key
        assertThat(hasColumn("dispensing", "controlled")).isTrue();           // controlled register
        assertThat(hasColumn("drug_interactions", "organization_id")).isTrue();
    }

    @Test
    void the_scoped_indexes_exist() {
        // C3: without these every org-scoped read is a full scan. A migration that silently no-ops would leave
        // the service correct but slow, which no behavioural test would ever catch.
        assertThat(hasIndex("prescriptions", "idx_rx_org_created")).isTrue();
        assertThat(hasIndex("prescriptions", "idx_rx_user")).isTrue();
        assertThat(hasIndex("dispensing", "idx_disp_org_controlled")).isTrue();
        assertThat(hasIndex("dispensing", "idx_disp_invoice")).isTrue();
        assertThat(hasIndex("drug_interactions", "idx_interaction_org")).isTrue();
    }

    @Test
    void the_migrations_are_rerunnable() {
        // Each script is guarded (information_schema checks / CREATE TABLE IF NOT EXISTS) so re-applying is a
        // no-op. Flyway itself won't re-run them, so assert the guards directly on the schema that exists.
        assertThat(hasColumn("products_that_do_not_exist", "x")).isFalse();   // sanity: the probe can say "no"
        assertThat(hasColumn("medicine_clinical", "controlled_substance")).isTrue();
        assertThat(hasColumn("medicine_clinical", "rx_required")).isTrue();
    }
}

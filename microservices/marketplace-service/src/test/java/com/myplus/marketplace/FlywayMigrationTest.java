package com.myplus.marketplace;

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
 * Standards review 2026-08-10 — <b>D2 was never satisfied for this service.</b>
 *
 * <p>D2 requires at least one test per service to boot with {@code flyway.enabled=true} and
 * {@code ddl-auto=validate} against an empty database. marketplace-service had none: every existing test runs
 * {@code flyway.enabled=false} with {@code ddl-auto=create-drop}, i.e. against a schema Hibernate invents. So
 * <b>all seventeen migrations were unexercised by {@code mvn test}</b> — including the two kinds of SQL that
 * actually break, both of which this service is full of:
 *
 * <ul>
 *   <li><b>The {@code fulfilment_status} ENUM widenings (V7 → V15 → V16).</b> Hibernate maps the Java enum as a
 *       string into a real MySQL {@code ENUM}, so every new constant needs an {@code ALTER … MODIFY} or the
 *       insert dies at runtime with <i>"Data truncated for column 'fulfilment_status'"</i>. Three separate
 *       migrations exist only to widen it, and nothing checked that the chain ends up covering the Java enum.
 *       That is asserted here directly, value by value.</li>
 *   <li><b>V11's ordering.</b> It backfills {@code order_seq}/{@code order_no} and only then adds
 *       {@code UNIQUE(organization_id, order_seq)} — because the constraint would trip over the NULLs it exists
 *       to prevent. On a fresh database that backfill touches nothing, which is precisely the branch a
 *       customer's first install takes and the one no dev database ever exercises.</li>
 * </ul>
 *
 * <p>This is the pharma incident repeating: there, V2's rebase and V3's rename — the most intricate SQL in the
 * service — had never run in CI either.
 *
 * <p>Real MySQL, because the migrations use MySQL-specific {@code PREPARE}/{@code information_schema} guards.
 * Skips without Docker (this machine runs the LOCAL stack, so it will skip here and run in CI).
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

    private String columnType(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
    }

    @Test
    void every_migration_applies_to_an_empty_database() throws java.io.IOException {
        // Booting at all is most of the assertion: ddl-auto=validate means Hibernate has already agreed that
        // what Flyway built matches every entity. This pins the set so a new migration is proven, not assumed.
        //
        // The expected list is DERIVED FROM THE MIGRATION FILES, not typed out. A hardcoded list asserts "there
        // are exactly the 17 I wrote down", which is not the property worth testing and which every subsequent
        // migration falsifies: V18-V23 were added and this test reddened with nothing wrong. Updating the
        // literal would have been a mechanical edit carrying no verification at all.
        //
        // Derived, it asserts the thing that matters: EVERY migration on disk applied cleanly to an empty
        // database, in order, none skipped and none failed. A new migration is then covered the moment it is
        // added, and one that fails to apply still fails here.
        //
        // Same fix, same reason as verify-schemas.sh in the deploy scripts, where a typed expectation went
        // stale four times before it was derived from disk instead. The sibling test below already works this
        // way, deriving from the Java enum.
        List<String> expected = migrationVersionsOnDisk();
        assertThat(expected).as("no migrations found on the classpath - the test would pass vacuously")
                .isNotEmpty();

        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL "
                        + "ORDER BY installed_rank", String.class);
        assertThat(applied)
                .as("every migration in db/migration must apply to an empty database, in version order")
                .containsExactlyElementsOf(expected);
    }

    /** Versions of every {@code V<n>__*.sql} in {@code db/migration}, in numeric order. */
    private List<String> migrationVersionsOnDisk() throws java.io.IOException {
        org.springframework.core.io.Resource[] files =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/migration/V*__*.sql");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^V([0-9]+)__");
        List<String> versions = new java.util.ArrayList<>();
        for (org.springframework.core.io.Resource r : files) {
            String name = r.getFilename();
            if (name == null) continue;
            java.util.regex.Matcher m = p.matcher(name);
            if (m.find()) versions.add(m.group(1));
        }
        versions.sort(java.util.Comparator.comparingInt(Integer::parseInt));
        return versions;
    }

    /**
     * The one that would actually take the service down.
     *
     * <p>Every value of the Java {@code FulfilmentStatus} enum must exist in the column's MySQL ENUM. Miss one
     * and the code compiles, the unit tests pass against Hibernate's invented schema, and the first order that
     * reaches that state dies on insert. Derived from the Java enum rather than a hardcoded list, so adding a
     * constant without its {@code ALTER … MODIFY} fails HERE instead of in production.
     */
    @Test
    void the_fulfilment_status_enum_covers_every_java_constant() {
        String type = columnType("orders", "fulfilment_status");
        assertThat(type).as("orders.fulfilment_status should be a MySQL ENUM").startsWith("enum(");
        for (com.myplus.marketplace.entity.FulfilmentStatus s
                : com.myplus.marketplace.entity.FulfilmentStatus.values()) {
            assertThat(type)
                    .as("V7/V15/V16 must widen the ENUM for %s, or inserting it fails with "
                            + "\"Data truncated for column 'fulfilment_status'\"", s)
                    .contains("'" + s.name() + "'");
        }
    }

    @Test
    void order_identity_and_idempotency_constraints_exist() {
        // V11, in its mandated order: columns, then BACKFILL, then the constraints. The unique keys are what
        // make MAX+1 numbering and OMS-3's duplicate-key catch race-safe — without them both silently degrade
        // to "usually fine", which no functional test would notice.
        assertThat(hasColumn("orders", "order_seq")).isTrue();
        assertThat(hasColumn("orders", "order_no")).isTrue();
        assertThat(hasColumn("orders", "idempotency_key")).isTrue();
        assertThat(hasColumn("orders", "version")).isTrue();          // O2's @Version
        assertThat(hasIndex("orders", "uq_order_org_seq")).isTrue();
        assertThat(hasIndex("orders", "uq_order_org_idem")).isTrue();
    }

    @Test
    void the_fulfilment_columns_the_later_slices_added_exist() {
        assertThat(hasColumn("order_items", "quantity_shipped")).isTrue();       // V15, O5b
        assertThat(hasColumn("order_items", "quantity_backordered")).isTrue();   // V16, O5c
        assertThat(hasColumn("order_items", "product_name")).isTrue();           // V14, O4 snapshot
        assertThat(hasColumn("orders", "promised_date")).isTrue();               // V16, O5c
        assertThat(hasColumn("orders", "books_status")).isTrue();                // V10, O1
        // V17 (O5d). The two packing SETTINGS were withdrawn in the 2026-08-10 review, but this column stays:
        // it is applied, it is honest (a pre-workbench parcel WAS typed), and it is what the workbench writes.
        assertThat(hasColumn("shipment_line", "verified")).isTrue();
    }

    @Test
    void every_scoped_read_has_an_index_behind_it() {
        // D3: organization_id carries the platform's most common predicate; unindexed, every scoped read is a
        // full scan. D3b: and the index must serve the QUERY, not merely the table — idx_orders_org_created is
        // what lets the paged list's LIMIT stop early instead of sorting the tenant's whole history.
        assertThat(hasIndex("orders", "idx_orders_org_created")).isTrue();     // V13, the paged list
        assertThat(hasIndex("orders", "idx_orders_books_status")).isTrue();    // V10, reconciliation
        assertThat(hasIndex("orders", "idx_orders_org_promised")).isTrue();    // V16, the late filter
        assertThat(hasIndex("orders", "idx_orders_order_no")).isTrue();        // V11, public tracking
        assertThat(hasIndex("order_items", "idx_order_item_order")).isTrue();
    }

    @Test
    void the_probe_can_say_no() {
        // Sanity: every assertion above is a COUNT(*) > 0, so a broken probe would make them all pass.
        assertThat(hasColumn("orders", "a_column_that_does_not_exist")).isFalse();
        assertThat(hasIndex("orders", "an_index_that_does_not_exist")).isFalse();
    }
}

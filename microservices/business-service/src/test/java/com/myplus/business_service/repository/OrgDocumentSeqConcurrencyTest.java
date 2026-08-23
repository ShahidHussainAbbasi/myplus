package com.myplus.business_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The document-number allocator, under actual concurrency, against actual MySQL.
 *
 * <h3>Why this cannot be a unit test</h3>
 * The whole mechanism IS the database's row lock. An in-memory fake, or H2, or a mock repository would prove
 * only that the Java reads what it wrote. What has to be true is that when two connections bump the same
 * counter at the same instant, <b>one of them waits</b> — and only a real InnoDB row lock does that.
 *
 * <p>This is also the shape of the defect it replaces: {@code MAX(seq) + 1} passes every single-threaded test
 * ever written and fails the moment a shop opens a second till. A test that never runs two threads could not
 * have caught it, which is exactly why it was not caught.
 *
 * <p>Runs on {@code mvn test} — the parent pom pins {@code docker.api.version}, without which every
 * Testcontainers test on this machine skipped silently inside a green build.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
// ⚠ NOT_SUPPORTED: @DataJpaTest wraps every test in a transaction it rolls back at the end. This class opens
// its OWN connections on purpose — that is the entire point, since one connection cannot contend with itself
// — and the test-managed transaction then holds locks the raw connections wait on, timing out after InnoDB's
// default 50 seconds. Four single-threaded cases failed that way while the concurrent one passed, which reads
// as nonsense until you notice the wrapper.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrgDocumentSeqConcurrencyTest {

    private static final String CREDIT_NOTE = "CREDIT_NOTE";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        // The concurrency case holds one connection per till AND briefly takes a second to create a missing
        // counter. With the default pool of 10 the threads starve each other waiting for connections and the
        // run dies in lock-wait timeouts that look exactly like a database deadlock but are not one.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    }

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) jdbc = new JdbcTemplate(dataSource);
        return jdbc;
    }

    /**
     * One allocation, in its own transaction, exactly as {@code DocumentNumberService.next} performs it.
     *
     * <p>Deliberately raw JDBC rather than the service: this is about what the DATABASE does under
     * contention, and going through Spring's proxy would put a transaction manager between the test and the
     * thing being tested.
     */
    private long allocate(Long orgId, String docType) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // ⚠ THESE STATEMENTS MUST MATCH DocumentNumberService.next() EXACTLY, in the same ORDER and with
            // the same transaction boundaries. Raw JDBC because the point is what the DATABASE does when
            // connections contend; a Spring proxy between the test and the lock would prove less. The cost of
            // that choice is this duplication — if the service changes shape and this does not, the test goes
            // on passing while proving nothing about the code that runs.
            long allocated;

            // Plain read FIRST, before this connection holds any lock. An UPDATE that matches zero rows takes
            // a GAP LOCK, which blocked the separate connection creating the row — the caller deadlocking
            // against itself, deterministically, on every first allocation.
            boolean exists;
            try (var chk = conn.prepareStatement(
                    "SELECT next_val FROM org_document_seq WHERE organization_id = ? AND doc_type = ?")) {
                chk.setLong(1, orgId);
                chk.setString(2, docType);
                try (var rs = chk.executeQuery()) { exists = rs.next(); }
            }
            if (!exists) ensureCounter(orgId, docType);

            try (var upd = conn.prepareStatement(
                    "UPDATE org_document_seq SET next_val = next_val + 1, updated = NOW() "
                            + "WHERE organization_id = ? AND doc_type = ?")) {
                upd.setLong(1, orgId);
                upd.setString(2, docType);
                upd.executeUpdate();
            }

            try (var sel = conn.prepareStatement(
                    "SELECT next_val FROM org_document_seq WHERE organization_id = ? AND doc_type = ?")) {
                sel.setLong(1, orgId);
                sel.setString(2, docType);
                try (var rs = sel.executeQuery()) {
                    rs.next();
                    allocated = rs.getLong(1);
                }
            }
            conn.commit();
            return allocated;
        }
    }

    /** Mirrors DocumentNumberService.ensureCounter: own connection, own transaction, commits at once. */
    private void ensureCounter(Long orgId, String docType) throws Exception {
        try (var c = dataSource.getConnection();
             var ins = c.prepareStatement(
                     "INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated) "
                             + "VALUES (?, ?, 0, NOW())")) {
            ins.setLong(1, orgId);
            ins.setString(2, docType);
            ins.executeUpdate();
        }
    }

    @Test
    @DisplayName("the first document a tenant issues is number 1")
    void startsAtOne() throws Exception {
        assertThat(allocate(900L, CREDIT_NOTE)).isEqualTo(1L);
        assertThat(allocate(900L, CREDIT_NOTE)).isEqualTo(2L);
        assertThat(allocate(900L, CREDIT_NOTE)).isEqualTo(3L);
    }

    @Test
    @DisplayName("counters are per TENANT and per DOCUMENT TYPE — tenant 13 and tenant 20 both have a note 1")
    void countersAreIndependent() throws Exception {
        assertThat(allocate(901L, CREDIT_NOTE)).isEqualTo(1L);
        assertThat(allocate(902L, CREDIT_NOTE)).isEqualTo(1L);   // a different shop, its own numbering
        assertThat(allocate(901L, "DEBIT_NOTE")).isEqualTo(1L);  // a different document, its own numbering
        assertThat(allocate(901L, CREDIT_NOTE)).isEqualTo(2L);   // and the first counter carried on
    }

    // ── ⭐ THE CASE THE OLD CODE COULD NOT PASS ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ 20 tills allocating at once get 20 DIFFERENT numbers, with no gaps")
    void concurrentAllocationIsSerial() throws Exception {
        final int tills = 20;
        final Long org = 903L;

        // The counter already exists, which is the realistic case: V45 seeds every tenant that has issued a
        // document, and first-time creation is covered separately by startsAtOne() below. Hammering creation
        // AND allocation at once tests two different things and tells you which failed only by guesswork.
        ensureCounter(org, CREDIT_NOTE);

        ExecutorService pool = Executors.newFixedThreadPool(tills);
        CountDownLatch startTogether = new CountDownLatch(1);

        // Every thread blocks on the same latch so they contend for the counter in the same instant, rather
        // than politely queueing behind each other's start-up. MAX(seq)+1 survives a loop; it does not
        // survive this.
        List<Callable<Long>> jobs = IntStream.range(0, tills)
                .mapToObj(i -> (Callable<Long>) () -> {
                    startTogether.await();
                    return allocate(org, CREDIT_NOTE);
                })
                .collect(Collectors.toList());

        List<Future<Long>> futures = jobs.stream().map(pool::submit).collect(Collectors.toList());
        startTogether.countDown();

        Set<Long> allocated = new ConcurrentSkipListSet<>();
        for (Future<Long> f : futures) {
            allocated.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // NO DUPLICATES — the property. Two tills holding the same credit-note number is the corruption the
        // UNIQUE constraint used to prevent by failing one of the sales outright.
        assertThat(allocated).hasSize(tills);

        // AND NO GAPS: exactly 1..20. A counter that merely avoided duplicates by skipping ahead would pass
        // the assertion above and still leave holes in a tax document series.
        assertThat(allocated).containsExactlyElementsOf(
                IntStream.rangeClosed(1, tills).mapToObj(Long::valueOf).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("⭐ a rolled-back operation RETURNS its number — the numbering stays gapless")
    void rollbackReturnsTheNumber() throws Exception {
        final Long org = 904L;
        assertThat(allocate(org, CREDIT_NOTE)).isEqualTo(1L);

        // A return that takes a number and then fails. Because the bump joins the caller's transaction
        // (DocumentNumberService is MANDATORY), the counter unwinds with it.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var upd = conn.prepareStatement(
                    "UPDATE org_document_seq SET next_val = next_val + 1 "
                            + "WHERE organization_id = ? AND doc_type = ?")) {
                upd.setLong(1, org);
                upd.setString(2, CREDIT_NOTE);
                upd.executeUpdate();
            }
            conn.rollback();
        }

        // 2 goes to the next caller rather than being burned. This is the entire argument for MANDATORY over
        // REQUIRES_NEW: an independently committed counter would hand out 3 here and leave an unexplained
        // hole at 2 for somebody to answer for at an audit.
        assertThat(allocate(org, CREDIT_NOTE)).isEqualTo(2L);
    }

    @Test
    @DisplayName("the counter table survives a re-run of its own seeding")
    void seedingIsIdempotent() throws Exception {
        // V45 seeds with INSERT IGNORE because FlywayConfig repairs and migrates on every start. Re-running
        // the seed must not reset a live counter back to a tenant's old maximum.
        assertThat(allocate(905L, CREDIT_NOTE)).isEqualTo(1L);
        assertThat(allocate(905L, CREDIT_NOTE)).isEqualTo(2L);

        // V45's seeding shape: INSERT IGNORE, which must be a no-op against a counter already in use.
        jdbc().update("INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated) "
                + "VALUES (?, ?, 0, NOW())", 905L, CREDIT_NOTE);

        assertThat(allocate(905L, CREDIT_NOTE)).isEqualTo(3L);
    }
}

package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DOC-INT B — the receipt counter under actual concurrency, against actual MySQL.
 *
 * <h3>Why this cannot be a unit test</h3>
 * The whole mechanism IS InnoDB's row lock. A mock proves only that the Java reads what it wrote; what has to be
 * true is that when twelve connections bump one counter at the same instant, eleven of them WAIT. {@code COUNT + 1}
 * passed every single-threaded test ever written — which is exactly why it was not caught.
 *
 * <p>Mirrors business-service's {@code OrgDocumentSeqConcurrencyTest}. Skips without Docker — read the SKIP count.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(DocumentNumberService.class)
// ⚠ NOT_SUPPORTED: @DataJpaTest wraps each test in a transaction it rolls back. This class opens its OWN
// transactions on purpose (one connection cannot contend with itself), and a test-managed transaction would hold
// locks the others wait on until InnoDB's 50-second timeout — business's copy of this test paid for that lesson.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReceiptNumberConcurrencyTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
        // One connection per racing receipt plus the brief second one that creates a missing counter. The
        // default pool of 10 starves the threads into lock-wait timeouts that look like deadlocks and are not.
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    }

    @Autowired private DocumentNumberService numbers;
    @Autowired private PlatformTransactionManager txm;

    /** One receipt's allocation, in its own transaction — as PaymentService.record takes it. */
    private long allocate(long org, String docType) {
        Long n = new TransactionTemplate(txm).execute(s -> numbers.next(org, docType));
        return n == null ? -1 : n;
    }

    @Test
    void a_tenants_first_receipt_is_number_one_and_the_next_is_two() {
        assertThat(allocate(101L, "RECEIPT")).isEqualTo(1L);
        assertThat(allocate(101L, "RECEIPT")).isEqualTo(2L);
    }

    @Test
    void receipts_and_vouchers_are_separate_series_and_tenants_do_not_share() {
        assertThat(allocate(102L, "RECEIPT")).isEqualTo(1L);
        assertThat(allocate(102L, "DISBURSEMENT")).isEqualTo(1L);
        assertThat(allocate(103L, "RECEIPT")).isEqualTo(1L);
        assertThat(allocate(102L, "RECEIPT")).isEqualTo(2L);
    }

    @Test
    void twelve_concurrent_receipts_take_twelve_distinct_consecutive_numbers() throws Exception {
        final int racers = 12;
        final long org = 202L;   // a tenant with NO counter yet — its first allocation is the hardest case
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return allocate(org, "RECEIPT");
                }));
            }
            go.countDown();

            Set<Long> got = new TreeSet<>();
            for (Future<Long> f : futures) got.add(f.get(60, TimeUnit.SECONDS));

            assertThat(got).as("COUNT + 1 would have handed several of these one number")
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, racers).boxed().collect(Collectors.toList()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_refused_receipt_gives_its_number_back() {
        long org = 204L;
        assertThat(allocate(org, "RECEIPT")).isEqualTo(1L);
        // e.g. the GL post refused (closed period): the receipt rolls back, and the bump with it
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            assertThat(numbers.next(org, "RECEIPT")).isEqualTo(2L);
            s.setRollbackOnly();
        });
        assertThat(allocate(org, "RECEIPT")).as("gapless: 2 was never issued").isEqualTo(2L);
    }

    @Test
    void outside_a_transaction_it_refuses_rather_than_committing_a_bump_on_its_own() {
        assertThrows(IllegalTransactionStateException.class, () -> numbers.next(205L, "RECEIPT"));
    }

    @Test
    void no_organisation_no_number() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransactionTemplate(txm).execute(s -> numbers.next(null, "RECEIPT")));
    }
}

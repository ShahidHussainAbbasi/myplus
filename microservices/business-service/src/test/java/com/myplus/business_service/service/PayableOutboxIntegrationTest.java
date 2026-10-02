package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.business_service.entity.PayableOutbox;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.repository.PayableOutboxRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.common.outbox.OutboxRelay;

/**
 * FP-2 — every committed change to a supplier purchase leaves exactly one payable outbox row with its FINAL figures,
 * against real MySQL and real transactions (the commit order is the thing under test; a mock cannot show it).
 *
 * <p>Finance is absent here, so rows stay PENDING and can be read back. Skips without Docker — read the Skipped count.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ PayableOutboxService.class, PurchasePayableListener.class, OutboxRelay.class })
class PayableOutboxIntegrationTest {

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
        r.add("logging.level.com.myplus.business_service.service.PurchasePayableListener", () -> "DEBUG");
        r.add("logging.level.com.myplus.business_service.service.PayableOutboxService", () -> "DEBUG");
    }

    @Autowired private PurchaseRepo purchases;
    @Autowired private PayableOutboxRepo outbox;
    @Autowired private PayableOutboxService service;
    @Autowired private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
    }

    private Long newPurchase(Long vendor, String paid, String due) {
        return tx.execute(s -> {
            Purchase p = new Purchase();
            p.setOrganizationId(6L);
            p.setUserId(60L);
            p.setVenderId(vendor);
            p.setPaidAmount(new BigDecimal(paid));
            p.setDueAmount(new BigDecimal(due));
            p.setPurchaseInvoiceNo("FP-" + System.nanoTime());
            p.setDated(LocalDateTime.now());
            return purchases.save(p).getPurchaseId();
        });
    }

    private List<PayableOutbox> rowsFor(Long purchaseId) {
        return outbox.findAll().stream().filter(o -> purchaseId.equals(o.getPurchaseId())).toList();
    }

    @Test @DisplayName("a new supplier purchase leaves one row: amount = bill, paid as recorded")
    void insert_reported() {
        Long id = newPurchase(9L, "0", "-100");
        List<PayableOutbox> rows = rowsFor(id);
        assertThat(rows).hasSize(1);
        var s = service.read(rows.get(0).getPayload());
        assertThat(s.getAmount()).isEqualByComparingTo("100");
        assertThat(s.getPaid()).isEqualByComparingTo("0");
        assertThat(s.getSource()).isEqualTo("PURCHASE");
        assertThat(s.getPartyId()).isEqualTo(9L);
    }

    @Test @DisplayName("⭐ an UPDATE flushed only at commit is still reported (the flush-before-write fix)")
    void dirty_update_without_save_reported() {
        Long id = newPurchase(9L, "0", "-100");
        outbox.deleteAll();
        tx.executeWithoutResult(s -> {
            Purchase p = purchases.findById(id).orElseThrow();
            p.setPaidAmount(new BigDecimal("40"));       // no save() — a managed entity, flushed at commit
            p.setDueAmount(new BigDecimal("-60"));
        });
        List<PayableOutbox> rows = rowsFor(id);
        assertThat(rows).as("the payment must reach finance").hasSize(1);
        assertThat(service.read(rows.get(0).getPayload()).getPaid()).isEqualByComparingTo("40");
    }

    @Test @DisplayName("several changes in one transaction → ONE row with the final figures")
    void last_write_wins() {
        Long id = newPurchase(9L, "0", "-100");
        outbox.deleteAll();
        tx.executeWithoutResult(s -> {
            Purchase p = purchases.findById(id).orElseThrow();
            p.setPaidAmount(new BigDecimal("10"));
            p.setDueAmount(new BigDecimal("-90"));
            purchases.saveAndFlush(p);
            p.setPaidAmount(new BigDecimal("30"));
            p.setDueAmount(new BigDecimal("-70"));
        });
        List<PayableOutbox> rows = rowsFor(id);
        assertThat(rows).hasSize(1);
        assertThat(service.read(rows.get(0).getPayload()).getPaid()).isEqualByComparingTo("30");
    }

    @Test @DisplayName("a rolled-back change reports nothing")
    void rollback_reports_nothing() {
        Long id = newPurchase(9L, "0", "-100");
        outbox.deleteAll();
        tx.executeWithoutResult(s -> {
            Purchase p = purchases.findById(id).orElseThrow();
            p.setPaidAmount(new BigDecimal("99"));
            s.setRollbackOnly();
        });
        assertThat(rowsFor(id)).isEmpty();
    }

    @Test @DisplayName("a cash purchase (no supplier) owes nothing and is not reported")
    void cash_purchase_ignored() {
        Long id = newPurchase(null, "100", "0");
        assertThat(rowsFor(id)).isEmpty();
    }

    @Test @DisplayName("a voided purchase is reported VOID")
    void void_reported() {
        Long id = newPurchase(9L, "0", "-100");
        outbox.deleteAll();
        tx.executeWithoutResult(s -> purchases.findById(id).orElseThrow().setStatus("VOID"));
        assertThat(service.read(rowsFor(id).get(0).getPayload()).isVoided()).isTrue();
    }
}

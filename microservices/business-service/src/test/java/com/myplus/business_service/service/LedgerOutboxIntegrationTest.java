package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.subledger.LedgerOutbox;

/**
 * FP-5a on real MySQL (V76's table): a settlement's payment reaches finance EXACTLY ONCE — or not at all.
 * The ledger table is created from the real migration file, so the test cannot drift from what deploys.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerOutboxIntegrationTest {

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
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private final FinanceClient finance = mock(FinanceClient.class);
    private LedgerOutbox ledger;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS ledger_payment_outbox");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V76__ledger_payment_outbox.sql"))
                .replaceAll("(?m)^--.*$", ""));
        @SuppressWarnings("unchecked")
        ObjectProvider<FinanceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(finance);
        when(provider.getObject()).thenReturn(finance);
        ledger = new LedgerOutbox(jdbc, provider, new OutboxRelay());
        AuthenticatedUser u = new AuthenticatedUser(60L, "owner@shop", List.of(new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), 6L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        reset(finance);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static PaymentRecordRequest req(String ref) {
        return PaymentRecordRequest.builder().direction("DISBURSEMENT").partyType("VENDOR").partyId(9L)
                .amount(new BigDecimal("40")).method("CASH").clientRef(ref).build();
    }

    private Map<String, Object> row(String ref) {
        return jdbc.queryForMap("SELECT status, attempts, receipt_no, organization_id FROM ledger_payment_outbox WHERE client_ref = ?", ref);
    }

    @Test @DisplayName("finance answers → delivered right after commit, the number is known to the caller")
    void deliveredAfterCommit() {
        when(finance.recordPayment(any())).thenReturn(PaymentRecordResult.builder().receiptNo("PV-000001").build());
        tx.executeWithoutResult(s -> ledger.enqueue(req("R1")));
        assertThat(row("R1").get("status")).isEqualTo("POSTED");
        assertThat(row("R1").get("organization_id")).isEqualTo(6L);
        assertThat(ledger.voucherFor("R1")).contains("PV-000001");
        Map<String, Object> resp = new HashMap<>(Map.of("ledgerRef", "R1"));
        ledger.fill(resp, "voucherNo");
        assertThat(resp.get("voucherNo")).isEqualTo("PV-000001");
    }

    @Test @DisplayName("⭐ finance DOWN → the settlement still commits, the request waits, then lands ONCE")
    void financeDownThenUp() {
        when(finance.recordPayment(any())).thenThrow(new RuntimeException("finance unreachable"));
        tx.executeWithoutResult(s -> ledger.enqueue(req("R2")));
        assertThat(row("R2").get("status")).as("kept for the retry").isEqualTo("PENDING");
        assertThat(((Number) row("R2").get("attempts")).intValue()).isEqualTo(1);
        Map<String, Object> resp = new HashMap<>(Map.of("ledgerRef", "R2"));
        ledger.fill(resp, "voucherNo");
        assertThat(resp).containsEntry("voucherPending", true).doesNotContainKey("voucherNo");

        reset(finance);
        when(finance.recordPayment(any())).thenReturn(PaymentRecordResult.builder().receiptNo("PV-000002").build());
        ledger.flushPending();
        ledger.flushPending();   // a second round must not send it again
        assertThat(row("R2").get("status")).isEqualTo("POSTED");
        assertThat(ledger.voucherFor("R2")).contains("PV-000002");
        verify(finance, times(1)).recordPayment(org.mockito.ArgumentMatchers.argThat(r -> "R2".equals(r.getClientRef())));
    }

    @Test @DisplayName("a rolled-back settlement sends nothing — no voucher for documents that were never applied")
    void rollbackSendsNothing() {
        tx.executeWithoutResult(s -> {
            ledger.enqueue(req("R3"));
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_payment_outbox WHERE client_ref = 'R3'", Integer.class)).isZero();
        verify(finance, times(0)).recordPayment(any());
    }

    @Test @DisplayName("the same reference twice is refused at the source (UNIQUE) — one request per settlement")
    void sameReferenceOnce() {
        when(finance.recordPayment(any())).thenReturn(PaymentRecordResult.builder().receiptNo("PV-000004").build());
        tx.executeWithoutResult(s -> ledger.enqueue(req("R4")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.enqueue(req("R4"))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(finance, times(1)).recordPayment(any());
    }
}

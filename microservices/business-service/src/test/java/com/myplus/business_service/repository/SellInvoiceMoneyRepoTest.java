package com.myplus.business_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;

/**
 * Tech-debt #12 (slice 29 expansion) — repository tests against real MySQL (Testcontainers) covering:
 *   * Sell.findScoped — tenant isolation + newest-first ordering (slice 21)
 *   * Sell.findScoped(Pageable) — opt-in pagination (slice 24)
 *   * CustomerHistoryRepo.maxInvoiceSeqForOrg — per-org invoice counter (slice 22)
 *   * money round-trips at DECIMAL(19,2) precision (slice 23)
 * Skips cleanly when Docker is absent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SellInvoiceMoneyRepoTest {

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
    }

    @Autowired
    private SellRepo sellRepo;
    @Autowired
    private CustomerHistoryRepo chRepo;
    @Autowired
    private TestEntityManager em;

    private Sell sell(Long org, Long user, String total) {
        Sell s = new Sell();
        s.setUserId(user);
        s.setOrganizationId(org);
        s.setTotalAmount(new BigDecimal(total));
        return em.persistAndFlush(s);
    }

    private CustomerHistory invoice(Long org, Long seq) {
        CustomerHistory h = new CustomerHistory();
        h.setUserId(1L);
        h.setOrganizationId(org);
        h.setInvoiceSeq(seq);
        return em.persistAndFlush(h);
    }

    @Test
    void sellFindScoped_isolates_by_org_and_orders_newest_first() {
        Sell a = sell(1L, 1L, "10.00");
        Sell b = sell(1L, 2L, "20.00");          // same org, other user — still visible
        Sell other = sell(2L, 3L, "30.00");      // another tenant — excluded

        List<Sell> scoped = sellRepo.findScoped(1L, 1L);

        assertThat(scoped).extracting(Sell::getSellId).containsExactly(b.getSellId(), a.getSellId());
        assertThat(scoped).extracting(Sell::getSellId).doesNotContain(other.getSellId());
    }

    @Test
    void sellFindScoped_paged_limits_rows() {
        for (int i = 0; i < 5; i++) {
            sell(1L, 1L, "1.00");
        }
        assertThat(sellRepo.findScoped(1L, 1L, PageRequest.of(0, 2))).hasSize(2);
    }

    @Test
    void maxInvoiceSeqForOrg_is_per_org() {
        invoice(1L, 1L);
        invoice(1L, 2L);
        invoice(1L, 3L);
        invoice(2L, 1L);

        assertThat(chRepo.maxInvoiceSeqForOrg(1L)).isEqualTo(3L);
        assertThat(chRepo.maxInvoiceSeqForOrg(2L)).isEqualTo(1L);
        assertThat(chRepo.maxInvoiceSeqForOrg(999L)).isEqualTo(0L); // COALESCE -> 0 when none
    }

    @Test
    void money_round_trips_at_two_decimal_precision() {
        Sell s = sell(1L, 1L, "99.99");
        em.flush();
        em.clear();
        Sell reloaded = sellRepo.findById(s.getSellId()).orElseThrow();
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("99.99");
    }
}

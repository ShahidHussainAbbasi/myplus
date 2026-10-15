package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.myplus.common.security.time.TenantClock;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.StockLevelRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * STK-ALERT — the header badge's counts, against real MySQL: low stock (own minimum wins, else the business cap; cap 0 =
 * own minimums only), out of stock, expired batches still holding stock, batches expiring inside the window — and
 * NEVER another tenant's. Design: selling-price-per-purchase-analysis.md §12.11. Skips without Docker
 * (run with -Dapi.version=1.41 on Windows).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class StockAlertSummaryTest {

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

    private static final Long ORG = 1L, OTHER = 2L, USER = 1L;

    @Autowired private StockService stockService;
    @Autowired private StockLevelRepository levels;
    @Autowired private StockEntryRepository entries;

    private static void as(Long orgId) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.myplus.common.security.AuthenticatedUser(USER, "t@t.com", List.of(), orgId), null, List.of()));
    }

    @AfterEach
    void signOut() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    private void level(Long org, long productId, String stock, String min) {
        levels.save(StockLevel.builder().productId(productId).currentStock(new BigDecimal(stock))
                .minStockLevel(min == null ? null : new BigDecimal(min)).organizationId(org).userId(USER).build());
    }

    private void batch(Long org, long productId, String qty, LocalDate expiry) {
        entries.save(StockEntry.builder().productId(productId).quantity(new BigDecimal(qty)).reservedQuantity(BigDecimal.ZERO)
                .batchNo("B" + productId + "-" + expiry).expiryDate(expiry).organizationId(org).userId(USER).build());
    }

    @BeforeEach
    void seed() {
        entries.deleteAll();
        levels.deleteAll();
        LocalDate today = TenantClock.today();
        level(ORG, 1, "3", null);       // low under a cap of 5
        level(ORG, 2, "8", "10");       // low by its OWN minimum, whatever the cap
        level(ORG, 3, "50", null);      // fine
        level(ORG, 4, "0", null);       // out of stock — counted as low and as out under a cap
        level(ORG, 5, "4", "2");        // own minimum 2 wins over a cap of 5: NOT low
        level(OTHER, 9, "1", "10");     // another tenant's: never counted

        batch(ORG, 1, "2", today.minusDays(1));       // expired, still holding stock
        batch(ORG, 2, "0", today.minusDays(3));       // expired but empty: not on the shelf, not counted
        batch(ORG, 3, "5", today);                    // expires TODAY: still sellable, so "expiring", not "expired"
        batch(ORG, 3, "5", today.plusDays(10));       // expiring within 30
        batch(ORG, 3, "5", today.plusDays(100));      // outside the window
        batch(OTHER, 9, "7", today.minusDays(5));     // another tenant's expired batch: never counted
    }

    @Test
    void with_a_cap_of_5_and_a_30_day_window() {
        as(ORG);
        Map<String, Object> s = stockService.alertSummary(5, 30);
        assertThat(s.get("low")).as("1 (cap), 2 (own min), 4 (out) — not 3, not 5 (own min 2 wins)").isEqualTo(3L);
        assertThat(s.get("out")).isEqualTo(1L);
        assertThat(s.get("expired")).as("the empty expired batch and the other tenant's are not counted").isEqualTo(1L);
        assertThat(s.get("expiring")).as("today and +10, not +100").isEqualTo(2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> low = (List<Map<String, Object>>) s.get("lowItems");
        assertThat(low).extracting(m -> ((Number) m.get("productId")).longValue()).containsExactly(4L, 1L, 2L);   // emptiest first
    }

    @Test
    void cap_0_means_own_minimums_only() {
        as(ORG);
        Map<String, Object> s = stockService.alertSummary(0, 30);
        assertThat(s.get("low")).as("only product 2, below its own minimum").isEqualTo(1L);
        assertThat(s.get("out")).as("product 4 has no minimum and there is no cap").isEqualTo(0L);
    }

    @Test
    void expiry_not_tracked_skips_both_expiry_figures_and_window_0_skips_expiring() {
        as(ORG);
        assertThat(stockService.alertSummary(5, -1)).doesNotContainKeys("expired", "expiring", "expiredItems", "expiringItems");
        Map<String, Object> s = stockService.alertSummary(5, 0);
        assertThat(s.get("expired")).isEqualTo(1L);
        assertThat(s).doesNotContainKeys("expiring", "expiringItems");
    }

    @Test
    void the_other_tenant_sees_only_its_own() {
        as(OTHER);
        Map<String, Object> s = stockService.alertSummary(0, 30);
        assertThat(s.get("low")).isEqualTo(1L);
        assertThat(s.get("expired")).isEqualTo(1L);
        assertThat(s.get("expiring")).isEqualTo(0L);
    }
}

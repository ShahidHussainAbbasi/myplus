package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.StockLevelRepository;

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
 * Slice 33, U2b — opening-stock seed against real MySQL: creates a StockLevel (currentStock + costPrice) and
 * an opening StockEntry per line. Skips without Docker; run via {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class StockImportServiceTest {

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

    private static final Long ORG = 1L, USER = 1L;

    @Autowired private StockImportService service;
    @Autowired private StockLevelRepository stockLevelRepository;
    @Autowired private StockEntryRepository stockEntryRepository;

    @BeforeEach
    void clean() {
        stockEntryRepository.deleteAll();
        stockLevelRepository.deleteAll();
    }

    @Test
    void seeds_stock_level_and_opening_entry() {
        var result = service.importStock(List.of(StockImportLine.builder()
                .productId(10L).quantity(25f).batchNo("B1").expiryDate(LocalDate.of(2026, 12, 1))
                .purchasePrice(new BigDecimal("5.00")).costPrice(new BigDecimal("5.00"))
                .build()), ORG, USER);

        assertThat(result.getCreated()).isEqualTo(1);
        // PERF-9: the write ANSWERS with the on-hand it just computed. Asserting only the count would leave
        // the reason the return type widened untested, and the Product screen's second round trip — the GET
        // this replaced — would come back unnoticed.
        assertThat(result.onHandFor(10L)).isEqualByComparingTo("25");
        var level = stockLevelRepository.findByProductScoped(10L, ORG, USER);
        assertThat(level).isPresent();
        assertThat(level.get().getCurrentStock()).isEqualByComparingTo("25");
        assertThat(level.get().getCostPrice()).isEqualByComparingTo("5.00");
        assertThat(stockEntryRepository.findAll()).singleElement()
                .satisfies(e -> {
                    assertThat(e.getProductId()).isEqualTo(10L);
                    assertThat(e.getQuantity()).isEqualByComparingTo("25");
                    assertThat(e.getBatchNo()).isEqualTo("B1");
                });
    }

    // ── PR-3b: the batch's own selling price ─────────────────────────────────────────────────────────

    @Autowired private StockService stockService;

    private static void as(Long orgId) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.myplus.common.security.AuthenticatedUser(USER, "t@t.com", List.of(), orgId), null, List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void signOut() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test
    void a_batch_is_booked_with_its_own_price_and_its_id_is_returned() {
        var result = service.importStock(List.of(StockImportLine.builder()
                .productId(10L).quantity(5f).purchasePrice(new BigDecimal("210.00")).sellPrice(new BigDecimal("250.00")).build(),
                StockImportLine.builder().productId(10L).quantity(3f).purchasePrice(new BigDecimal("200.00")).build()), ORG, USER);

        assertThat(result.getEntryIds()).hasSize(2);
        var priced = stockEntryRepository.findById(result.getEntryIds().get(0)).orElseThrow();
        var plain = stockEntryRepository.findById(result.getEntryIds().get(1)).orElseThrow();
        assertThat(priced.getSellPrice()).isEqualByComparingTo("250.00");
        assertThat(plain.getSellPrice()).as("no price sent = the product's price applies").isNull();
    }

    @Test
    void an_edit_that_only_changes_the_rate_re_prices_its_batch_and_only_this_tenants() {
        var result = service.importStock(List.of(StockImportLine.builder()
                .productId(10L).quantity(5f).purchasePrice(new BigDecimal("210.00")).sellPrice(new BigDecimal("250.00")).build()), ORG, USER);
        Long id = result.getEntryIds().get(0);

        as(2L);   // another tenant: not found, never written
        stockService.reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust.builder()
                .productId(10L).delta(0f).stockEntryId(id).sellPrice(new BigDecimal("1.00")).build());
        assertThat(stockEntryRepository.findById(id).orElseThrow().getSellPrice()).isEqualByComparingTo("250.00");

        as(ORG);  // its own tenant, delta 0: the price still changes
        stockService.reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust.builder()
                .productId(10L).delta(0f).stockEntryId(id).sellPrice(new BigDecimal("260.00")).build());
        var e = stockEntryRepository.findById(id).orElseThrow();
        assertThat(e.getSellPrice()).isEqualByComparingTo("260.00");
        assertThat(e.getQuantity()).as("quantity untouched by a price-only edit").isEqualByComparingTo("5");
    }

    @Test
    void tp3_an_edit_that_keeps_the_quantity_still_reaches_its_own_batch_and_only_that_one() {
        as(ORG);
        // Two bills, ONE batch number: by number either could match; by id only the edited bill's batch may change.
        var result = service.importStock(List.of(
                StockImportLine.builder().productId(10L).quantity(10f).batchNo("T25791").expiryDate(LocalDate.of(2027, 10, 9))
                        .purchasePrice(new BigDecimal("260.00")).paidTotal(new BigDecimal("2600.00")).build(),
                StockImportLine.builder().productId(10L).quantity(4f).batchNo("T25791").expiryDate(LocalDate.of(2027, 10, 9))
                        .purchasePrice(new BigDecimal("300.00")).paidTotal(new BigDecimal("1200.00")).build()), ORG, USER);
        Long mine = result.getEntryIds().get(0), other = result.getEntryIds().get(1);

        as(2L);   // another tenant: not found, never written
        stockService.reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust.builder()
                .productId(10L).batchNo("T25791").delta(0f).stockEntryId(mine).expiryDate(LocalDate.of(2000, 1, 1)).build());
        assertThat(stockEntryRepository.findById(mine).orElseThrow().getExpiryDate()).isEqualTo(LocalDate.of(2027, 10, 9));

        as(ORG);  // bill 3106: expiry and cost corrected, quantity kept (delta 0)
        stockService.reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust.builder()
                .productId(10L).batchNo("T25791").delta(0f).stockEntryId(mine).expiryDate(LocalDate.of(2027, 9, 9))
                .purchasePrice(new BigDecimal("250.00")).paidTotal(new BigDecimal("2500.00")).build());
        var e = stockEntryRepository.findById(mine).orElseThrow();
        assertThat(e.getExpiryDate()).isEqualTo(LocalDate.of(2027, 9, 9));
        assertThat(e.getPurchasePrice()).isEqualByComparingTo("250.00");
        assertThat(ReservationService.unitCostOf(e)).as("2500 / 10").isEqualByComparingTo("250");
        assertThat(e.getQuantity()).isEqualByComparingTo("10");

        // quantity 10 -> 12 by id: this batch grows and its divisor with it; the other bill's batch is untouched
        stockService.reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust.builder()
                .productId(10L).batchNo("T25791").delta(2f).stockEntryId(mine)
                .purchasePrice(new BigDecimal("250.00")).paidTotal(new BigDecimal("3000.00")).build());
        e = stockEntryRepository.findById(mine).orElseThrow();
        assertThat(e.getQuantity()).isEqualByComparingTo("12");
        assertThat(ReservationService.unitCostOf(e)).as("3000 / 12").isEqualByComparingTo("250");
        var o = stockEntryRepository.findById(other).orElseThrow();
        assertThat(o.getQuantity()).isEqualByComparingTo("4");
        assertThat(o.getExpiryDate()).isEqualTo(LocalDate.of(2027, 10, 9));
        assertThat(ReservationService.unitCostOf(o)).isEqualByComparingTo("300");
    }
}

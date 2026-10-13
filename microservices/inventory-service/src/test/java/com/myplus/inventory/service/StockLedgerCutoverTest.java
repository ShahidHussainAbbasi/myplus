package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.commerce.contracts.dto.StockMovementType;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.entity.StockMovement;
import com.myplus.inventory.repository.StockLevelRepository;
import com.myplus.inventory.repository.StockMovementRepository;

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
 * SSR-0b — the ledger's starting balance (one OPENING row per product, once) and the check that SUM(ledger) still
 * equals on-hand. Real MySQL (the reconciler is a native query).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class StockLedgerCutoverTest {

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

    private static final Long ORG = 81L, USER = 8L;

    @Autowired private StockLedgerCutover cutover;
    @Autowired private StockLevelRepository levels;
    @Autowired private StockMovementRepository ledger;
    @Autowired private StockLedgerReport report;

    @BeforeEach
    void clean() {
        ledger.deleteAll();
        levels.deleteAll();
    }

    private StockLevel level(Long org, long product, String onHand) {
        return levels.save(StockLevel.builder().productId(product).currentStock(new BigDecimal(onHand))
                .organizationId(org).userId(USER).build());
    }

    private void movement(long product, String qty, String type) {
        movement(product, qty, type, java.time.LocalDate.of(2026, 10, 1));
    }

    private void movement(long product, String qty, String type, java.time.LocalDate day) {
        ledger.save(StockMovement.builder().organizationId(ORG).userId(USER).productId(product).movementType(type)
                .quantity(new BigDecimal(qty)).businessDate(day)
                .createdAt(java.time.LocalDateTime.now()).build());
    }

    private List<StockMovement> openings(long product) {
        return ledger.findAll().stream()
                .filter(m -> m.getProductId() == product && StockMovementType.OPENING.equals(m.getMovementType())).toList();
    }

    @Test
    void every_product_gets_one_opening_equal_to_its_on_hand_and_a_second_run_adds_nothing() {
        level(ORG, 1, "12.5");
        level(ORG, 2, "0");
        assertThat(cutover.cutOver()).isEqualTo(2);

        assertThat(openings(1)).singleElement().satisfies(m -> {
            assertThat(m.getQuantity()).isEqualByComparingTo("12.5");
            assertThat(m.getSourceRef()).isEqualTo("GO-LIVE");
        });
        assertThat(openings(2)).as("a zero OPENING is the marker that the product is cut over").singleElement()
                .satisfies(m -> assertThat(m.getQuantity()).isEqualByComparingTo("0"));

        assertThat(cutover.cutOver()).as("idempotent").isZero();
        assertThat(ledger.count()).isEqualTo(2);
    }

    @Test
    void movements_recorded_before_the_cut_over_are_not_counted_twice() {
        level(ORG, 3, "10");
        movement(3, "3", StockMovementType.PURCHASE);   // recorded by SSR-0a before the job ran
        cutover.cutOver();
        assertThat(openings(3)).singleElement()
                .satisfies(m -> assertThat(m.getQuantity()).as("10 on hand − 3 already in the ledger").isEqualByComparingTo("7"));
        assertThat(cutover.differences(ORG)).isEmpty();
    }

    @Test
    void a_change_that_bypassed_the_ledger_is_reported_and_left_alone() {
        StockLevel l = level(ORG, 4, "5");
        level(ORG, 5, "2");
        cutover.cutOver();
        l.setCurrentStock(new BigDecimal("6"));   // a write that did not go through StockLedger
        levels.save(l);

        List<java.util.Map<String, Object>> diffs = cutover.differences(ORG);
        assertThat(diffs).singleElement().satisfies(d -> {
            assertThat(d.get("productId")).isEqualTo(4L);
            assertThat(new BigDecimal(String.valueOf(d.get("onHand")))).isEqualByComparingTo("6");
            assertThat(new BigDecimal(String.valueOf(d.get("ledger")))).isEqualByComparingTo("5");
        });
        assertThat(cutover.cutOver()).as("never re-opened to hide the difference").isZero();
        assertThat(cutover.differences(ORG)).hasSize(1);
    }

    @Test
    void another_business_is_not_in_this_business_s_differences_and_legacy_levels_are_not_cut_over() {
        StockLevel other = level(ORG + 1, 6, "1");
        level(null, 7, "4");
        cutover.cutOver();
        other.setCurrentStock(new BigDecimal("9"));
        levels.save(other);

        assertThat(cutover.differences(ORG)).isEmpty();
        assertThat(cutover.differences(ORG + 1)).hasSize(1);
        assertThat(openings(7)).as("no organisation, not cut over").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_period_summary_gives_opening_movements_by_type_and_closing() {
        java.time.LocalDate aug = java.time.LocalDate.of(2026, 8, 31), sep1 = java.time.LocalDate.of(2026, 9, 1),
                sep15 = java.time.LocalDate.of(2026, 9, 15), oct2 = java.time.LocalDate.of(2026, 10, 2);
        movement(8, "-8", StockMovementType.OPENING, aug);       // the PDF's negative opening
        movement(8, "564", StockMovementType.PURCHASE, sep1);
        movement(8, "-509", StockMovementType.SALE, sep15);      // 482 sold + 27 bonus, together
        movement(8, "42", StockMovementType.SALE_RETURN, sep15);
        movement(8, "100", StockMovementType.PURCHASE, oct2);    // after the period: not in it
        movement(9, "5", StockMovementType.OPENING, aug);        // nothing moved in the period

        java.util.Map<String, Object> out = report.periodSummary(ORG, sep1, java.time.LocalDate.of(2026, 9, 28));
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>((java.util.Collection<java.util.Map<String, Object>>) out.get("products"));
        java.util.Map<String, Object> acne = rows.stream().filter(r -> Long.valueOf(8).equals(r.get("productId"))).findFirst().orElseThrow();
        assertThat((BigDecimal) acne.get("opening")).isEqualByComparingTo("-8");
        java.util.Map<String, BigDecimal> mv = (java.util.Map<String, BigDecimal>) acne.get("movements");
        assertThat(mv.get(StockMovementType.PURCHASE)).isEqualByComparingTo("564");
        assertThat(mv.get(StockMovementType.SALE)).isEqualByComparingTo("-509");
        assertThat(mv.get(StockMovementType.SALE_RETURN)).isEqualByComparingTo("42");
        assertThat((BigDecimal) acne.get("closing")).as("the PDF's ACNE CARE: 89").isEqualByComparingTo("89");
        java.util.Map<String, Object> quiet = rows.stream().filter(r -> Long.valueOf(9).equals(r.get("productId"))).findFirst().orElseThrow();
        assertThat((BigDecimal) quiet.get("closing")).isEqualByComparingTo("5");
        assertThat((java.util.Map<String, BigDecimal>) quiet.get("movements")).isEmpty();
        assertThat(out.get("ledgerStart")).isEqualTo(aug);
    }
}

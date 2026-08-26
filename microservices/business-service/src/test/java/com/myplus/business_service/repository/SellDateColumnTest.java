package com.myplus.business_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.business_service.entity.Sell;

/**
 * A sale belongs to the month it HAPPENED in, not the month it was last edited in.
 *
 * <h3>The defect</h3>
 * Every date-ranged read of {@code Sell} — the dashboard's period figures, its charts, and the Sale Detail
 * Report — filtered {@code s.updated}. That column moves every time the row is touched, so editing an old
 * invoice silently removed it from its own month and added it to the current one. Two months' totals change
 * at once, both wrong, and nothing on either screen says so.
 *
 * <p>{@code dated} is {@code @Column(updatable=false)}: set when the sale is written and immutable
 * thereafter. It is what a sales report means by "date".
 *
 * <h3>The report was already inconsistent with itself</h3>
 * {@code findSellByStartDate} and {@code findSellByEndDate} both filtered {@code dated}. So on the same
 * screen, a start-and-end range meant one thing and a start-only range meant another — the same sale
 * appearing or not depending on which boxes the operator filled in. That is what settled the direction:
 * {@code dated} was already the majority answer.
 *
 * <h3>Why this test is worth more than the change it guards</h3>
 * The corrected behaviour is invisible until somebody edits an old invoice, and by then the figure has moved
 * and nobody connects it to an edit weeks earlier. A regression here would be silent in exactly the same way,
 * so the property is pinned rather than trusted.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SellDateColumnTest {

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

    @Autowired private SellRepo repo;
    @Autowired private TestEntityManager em;

    private static final Long ORG = 1L, USER = 1L;
    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 15, 10, 0);
    private static final LocalDateTime MAR = LocalDateTime.of(2026, 3, 20, 10, 0);
    private static final LocalDateTime JAN_START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime JAN_END = LocalDateTime.of(2026, 1, 31, 23, 59, 59);

    /**
     * A sale DATED in January whose row was last touched in March — an invoice corrected two months later.
     *
     * <p>{@code dated} carries {@code updatable=false}, so it cannot be moved after the insert; the value is
     * set on the way in and the later edit only moves {@code updated}. That is precisely the situation the
     * production data was one edit away from: 31 rows had been edited, and none had yet crossed a month.
     */
    private Sell sellDatedJanEditedMarch() {
        Sell s = new Sell();
        s.setOrganizationId(ORG);
        s.setUserId(USER);
        s.setQuantity(1f);
        s.setNetAmount(BigDecimal.valueOf(100));
        s.setTotalAmount(BigDecimal.valueOf(100));
        s.setDated(JAN);
        s.setUpdated(MAR);
        return em.persistAndFlush(s);
    }

    // ── the property ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("THE CASE — an invoice edited months later still reports in the month it was sold")
    void an_edited_sale_stays_in_its_own_month() {
        Sell s = sellDatedJanEditedMarch();

        List<Sell> january = repo.findSellByDates(JAN_START, JAN_END, ORG, USER);

        assertThat(january).extracting(Sell::getSellId)
                .as("dated January, edited March — it is a January sale")
                .contains(s.getSellId());
    }

    @Test
    @DisplayName("…and does NOT appear in the month it was edited in")
    void an_edited_sale_does_not_appear_in_the_edit_month() {
        /*
         * The other half, and the one that made two totals wrong at once: under the old column this sale
         * left January AND joined March. Asserting only that it is in January would pass even if it were in
         * both.
         */
        Sell s = sellDatedJanEditedMarch();

        List<Sell> march = repo.findSellByDates(
                LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 3, 31, 23, 59, 59), ORG, USER);

        assertThat(march).extracting(Sell::getSellId)
                .as("March is when it was corrected, not when it was sold")
                .doesNotContain(s.getSellId());
    }

    @Test
    @DisplayName("POSITIVE CONTROL — an ordinary unedited sale is still found")
    void an_unedited_sale_is_found_normally() {
        // Without this, a query that returned nothing at all would satisfy the "not in March" case perfectly.
        Sell s = new Sell();
        s.setOrganizationId(ORG);
        s.setUserId(USER);
        s.setQuantity(1f);
        s.setNetAmount(BigDecimal.valueOf(50));
        s.setTotalAmount(BigDecimal.valueOf(50));
        s.setDated(JAN);
        s.setUpdated(JAN);
        em.persistAndFlush(s);

        assertThat(repo.findSellByDates(JAN_START, JAN_END, ORG, USER))
                .extracting(Sell::getSellId).contains(s.getSellId());
    }

    // ── the aggregates must agree with the list ─────────────────────────────────────────────────

    @Test
    @DisplayName("the dashboard's aggregates use the same column as the report")
    void aggregates_agree_with_the_list() {
        /*
         * The screen reads three of these — a period total, a monthly trend and a daily series — and the
         * report reads a fourth. Two of them filtering different columns is how one figure on a dashboard
         * disagrees with another beside it, which is worse than both being wrong the same way.
         */
        sellDatedJanEditedMarch();

        Object[] agg = repo.sumSellByDates(JAN_START, JAN_END, ORG, USER);
        Object[] row = (agg != null && agg.length == 1 && agg[0] instanceof Object[]) ? (Object[]) agg[0] : agg;
        long counted = ((Number) row[0]).longValue();

        assertThat(counted)
                .as("the aggregate counts what the list returns")
                .isEqualTo(repo.findSellByDates(JAN_START, JAN_END, ORG, USER).size());

        assertThat(repo.monthlyTrendScoped(JAN_START, JAN_END, ORG, USER))
                .as("the trend sees the January sale too")
                .isNotEmpty();
        assertThat(repo.dailyRevenueScoped(JAN_START, JAN_END, ORG, USER))
                .as("and so does the daily series")
                .isNotEmpty();
    }
}

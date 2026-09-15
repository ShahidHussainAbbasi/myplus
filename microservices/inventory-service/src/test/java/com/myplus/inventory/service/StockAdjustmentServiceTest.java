package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.inventory.dto.StockDTOs.StockAdjustmentDTO;
import com.myplus.inventory.dto.StockDTOs.StockAdjustmentView;
import com.myplus.inventory.entity.StockAdjustment.AdjustmentType;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.repository.StockAdjustmentRepository;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.StockLevelRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BLK-5 — a stock correction against REAL MySQL: the unique index is the arbiter, so a mock repository could not
 * prove the property that matters (two racers, one key, one adjustment, stock moved once).
 *
 * <p>Design: microservices/docs/slices/blk-5-stock-adjust-guard.md. Skips (does not fail) without Docker. ⚠ On this
 * machine Testcontainers needs {@code -Dapi.version=1.41} (pinned in the parent pom); if the SKIPPED count is ever
 * non-zero, these assertions are not running.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class StockAdjustmentServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        // create-drop builds uq_adj_org_idem from the entity's @UniqueConstraint — the racer case needs it real.
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    private static final Long ORG = 1L, OTHER_ORG = 2L, USER = 7L, PRODUCT = 10L, UNKNOWN_PRODUCT = 404L;

    /** Catalog is another service: the existence check is answered here, and made to 404 where a case needs it. */
    @MockitoBean private CatalogClient catalogClient;

    @Autowired private StockAdjustmentService service;
    @Autowired private StockAdjustmentRepository adjustments;
    @Autowired private StockEntryRepository entries;
    @Autowired private StockLevelRepository levels;

    @BeforeEach
    void clean() {
        adjustments.deleteAll();
        entries.deleteAll();
        levels.deleteAll();
        stock(ORG, 10);
    }

    /** A batch AND the level, together — a DECREASE draws down batches, so a level alone could not be corrected. */
    private void stock(Long org, double qty) {
        entries.save(StockEntry.builder()
                .productId(PRODUCT).quantity(BigDecimal.valueOf(qty)).reservedQuantity(BigDecimal.ZERO)
                .batchNo("B-" + org).restockable(true).organizationId(org).userId(USER).build());
        levels.save(StockLevel.builder()
                .productId(PRODUCT).currentStock(BigDecimal.valueOf(qty)).organizationId(org).userId(USER).build());
    }

    private StockAdjustmentDTO dto(AdjustmentType type, float qty, String reason, String key) {
        return StockAdjustmentDTO.builder()
                .productId(PRODUCT).adjustmentType(type).quantity(qty).reason(reason).idempotencyKey(key).build();
    }

    private BigDecimal onHand(Long org) {
        return levels.findByProductScoped(PRODUCT, org, USER).map(StockLevel::getCurrentStock).orElse(BigDecimal.ZERO);
    }

    private int rowsFor(String key, Long org) {
        return adjustments.findByIdempotencyKeyScoped(key, org, USER).size();
    }

    @Test
    @DisplayName("⭐ one key sent twice moves the stock ONCE, and the second answer is a replay")
    void same_key_twice_moves_stock_once() {
        StockAdjustmentView first = service.adjust(dto(AdjustmentType.DECREASE, 3f, "Damaged", "k-twice"), ORG, USER);
        StockAdjustmentView again = service.adjust(dto(AdjustmentType.DECREASE, 3f, "Damaged", "k-twice"), ORG, USER);

        assertThat(first.isReplayed()).as("the first is the real write").isFalse();
        assertThat(first.getResultingOnHand()).as("the write reports the on-hand it produced").isEqualTo(7f);
        assertThat(again.isReplayed()).as("the repeat is a replay").isTrue();
        assertThat(again.getId()).as("of the SAME adjustment").isEqualTo(first.getId());
        assertThat(onHand(ORG)).as("moved once: 10 - 3").isEqualByComparingTo("7");
        assertThat(rowsFor("k-twice", ORG)).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐⭐ six requests racing with ONE key record one adjustment and move the stock once")
    void racers_with_one_key_record_one_adjustment() throws Exception {
        /*
         * THE CASE A PRE-CHECK ALONE FAILS. All six pass the "is this key used?" read before any of them commits,
         * so only uq_adj_org_idem can separate them — and only the catch-and-replay turns the five losers into
         * successful answers rather than errors (DUP-1: 148 overlapping requests, every pre-check empty).
         */
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<StockAdjustmentView>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.adjust(dto(AdjustmentType.DECREASE, 2f, "Counting error", "k-race"), ORG, USER);
            }));
        }
        go.countDown();

        int fresh = 0;
        for (Future<StockAdjustmentView> f : results) {
            StockAdjustmentView v = f.get(60, TimeUnit.SECONDS);   // an exception here = a racer answered with an error
            if (!v.isReplayed()) fresh++;
        }
        pool.shutdown();

        assertThat(fresh).as("exactly one racer did the work; five replayed it").isEqualTo(1);
        assertThat(rowsFor("k-race", ORG)).as("one adjustment carries the key").isEqualTo(1);
        assertThat(onHand(ORG)).as("moved ONCE: 10 - 2").isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("⭐ the same key with a DIFFERENT quantity is refused, never silently replayed")
    void same_key_different_quantity_is_refused() {
        service.adjust(dto(AdjustmentType.DECREASE, 3f, "Damaged", "k-diff"), ORG, USER);

        assertThatThrownBy(() -> service.adjust(dto(AdjustmentType.DECREASE, 5f, "Damaged", "k-diff"), ORG, USER))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already recorded");
        assertThat(onHand(ORG)).as("only the first moved").isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("⭐ a correction with no reason is refused and writes nothing")
    void blank_reason_is_refused_and_writes_nothing() {
        for (String reason : new String[] { null, "", "    " }) {
            assertThatThrownBy(() -> service.adjust(dto(AdjustmentType.DECREASE, 1f, reason, "k-noreason"), ORG, USER))
                    .as("reason %s", reason)
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("reason");
        }
        assertThat(onHand(ORG)).isEqualByComparingTo("10");
        assertThat(adjustments.count()).isZero();
    }

    @Test
    @DisplayName("TRANSFER, zero and negative quantities are refused — each used to write a wrong record")
    void transfer_and_non_positive_quantity_are_refused() {
        assertThatThrownBy(() -> service.adjust(dto(AdjustmentType.TRANSFER, 1f, "moving", null), ORG, USER))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.adjust(dto(AdjustmentType.DECREASE, 0f, "nothing", null), ORG, USER))
                .isInstanceOf(ValidationException.class);
        // A negative DECREASE used to ADD stock.
        assertThatThrownBy(() -> service.adjust(dto(AdjustmentType.DECREASE, -4f, "backwards", null), ORG, USER))
                .isInstanceOf(ValidationException.class);
        assertThat(onHand(ORG)).isEqualByComparingTo("10");
        assertThat(adjustments.count()).isZero();
    }

    @Test
    @DisplayName("⭐⭐ the record names WHO and WHICH SHOP from the caller — never from the body")
    void stamps_who_and_which_shop_from_the_caller() {
        StockAdjustmentDTO body = dto(AdjustmentType.DECREASE, 1f, "  Expired  ", "k-who");
        body.setAdjustedBy(999L);   // a body claiming to be someone else

        StockAdjustmentView v = service.adjust(body, ORG, USER);

        assertThat(v.getAdjustedBy()).as("the caller, not the body").isEqualTo(USER);
        assertThat(v.getOrganizationId()).as("the caller's shop").isEqualTo(ORG);
        assertThat(v.getReason()).as("trimmed").isEqualTo("Expired");
        assertThat(v.getIdempotencyKey()).isEqualTo("k-who");
    }

    @Test
    @DisplayName("⭐ the same key in ANOTHER shop is a new correction, not a replay of this one")
    void same_key_in_another_tenant_is_a_new_adjustment() {
        stock(OTHER_ORG, 5);
        StockAdjustmentView mine = service.adjust(dto(AdjustmentType.DECREASE, 1f, "Damaged", "k-shared"), ORG, USER);
        StockAdjustmentView theirs = service.adjust(dto(AdjustmentType.DECREASE, 1f, "Damaged", "k-shared"), OTHER_ORG, USER);

        assertThat(theirs.isReplayed()).as("a key is unique PER SHOP").isFalse();
        assertThat(theirs.getId()).isNotEqualTo(mine.getId());
        assertThat(onHand(ORG)).isEqualByComparingTo("9");
        assertThat(onHand(OTHER_ORG)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a refused correction leaves no record, so a corrected retry with the same key proceeds")
    void refused_correction_leaves_no_record() {
        assertThatThrownBy(() -> service.adjust(dto(AdjustmentType.DECREASE, 99f, "Damaged", "k-refused"), ORG, USER))
                .isInstanceOf(ValidationException.class);
        assertThat(rowsFor("k-refused", ORG)).as("the row rolled back with the refusal").isZero();

        StockAdjustmentView retry = service.adjust(dto(AdjustmentType.DECREASE, 2f, "Damaged", "k-refused"), ORG, USER);
        assertThat(retry.isReplayed()).as("nothing to replay — a real write").isFalse();
        assertThat(onHand(ORG)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("a correction for a product that is not the caller's is refused and writes nothing")
    void unknown_product_is_refused() {
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null))
                .when(catalogClient).getProduct(UNKNOWN_PRODUCT);
        StockAdjustmentDTO foreign = dto(AdjustmentType.INCREASE, 100f, "Counting error", "k-foreign");
        foreign.setProductId(UNKNOWN_PRODUCT);

        assertThatThrownBy(() -> service.adjust(foreign, ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Product not found");
        assertThat(adjustments.count()).isZero();
        assertThat(levels.findByProductScoped(UNKNOWN_PRODUCT, ORG, USER)).as("no orphan level").isEmpty();
    }

    @Test
    @DisplayName("the history is scoped to the caller's shop")
    void history_is_scoped_to_the_tenant() {
        stock(OTHER_ORG, 5);
        service.adjust(dto(AdjustmentType.DECREASE, 1f, "Damaged", "k-h1"), ORG, USER);
        service.adjust(dto(AdjustmentType.DECREASE, 1f, "Damaged", "k-h2"), OTHER_ORG, USER);

        List<StockAdjustmentView> mine = service.history(PRODUCT, ORG, USER);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getOrganizationId()).isEqualTo(ORG);
    }
}

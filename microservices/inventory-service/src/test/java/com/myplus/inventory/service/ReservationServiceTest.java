package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.commerce.contracts.dto.StockReturnLine;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.repository.ReservationRepository;
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
 * Slice 33, Phase 6a — the reservation saga participant against real MySQL. Proves FEFO allocation,
 * confirm decrements stock, release returns the hold, OUT_OF_STOCK holds nothing, and reserve is idempotent.
 * Skips (does not fail) without Docker; run via {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ReservationServiceTest {

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

    private static final Long ORG = 1L, USER = 1L, PRODUCT = 10L;
    // Relative to today so the tests don't rot, and so G1 (expired-stock exclusion) is exercised correctly.
    private static final LocalDate SOON = LocalDate.now().plusMonths(2);    // earlier expiry, still valid
    private static final LocalDate LATER = LocalDate.now().plusMonths(6);   // later expiry, still valid
    private static final LocalDate EXPIRED = LocalDate.now().minusDays(1);  // already expired

    @Autowired private ReservationService service;
    @Autowired private StockEntryRepository stockEntryRepository;
    @Autowired private StockLevelRepository stockLevelRepository;
    @Autowired private ReservationRepository reservationRepository;

    @BeforeEach
    void clean() {
        reservationRepository.deleteAll();
        stockEntryRepository.deleteAll();
        stockLevelRepository.deleteAll();
    }

    private StockEntry batch(float qty, LocalDate expiry) {
        StockEntry e = StockEntry.builder()
                .productId(PRODUCT).quantity(qty).reservedQuantity(0f).expiryDate(expiry)
                .batchNo("B" + expiry).organizationId(ORG).userId(USER).build();
        return stockEntryRepository.save(e);
    }

    private void stockLevel(float current) {
        stockLevelRepository.save(StockLevel.builder()
                .productId(PRODUCT).currentStock(current).organizationId(ORG).userId(USER).build());
    }

    private StockReservationRequest request(String key, float qty) {
        return new StockReservationRequest(key,
                List.of(new StockReservationLine(PRODUCT, BigDecimal.valueOf(qty))));
    }

    @Test
    void reserve_allocates_fefo_then_confirm_decrements_stock() {
        StockEntry early = batch(30f, SOON);
        StockEntry late = batch(50f, LATER);
        stockLevel(80f);

        StockReservationResponse res = service.reserve(request("k1", 40f), ORG, USER);

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        // FEFO: 30 from the earlier-expiry batch, 10 from the later one. Held, not yet decremented.
        assertThat(stockEntryRepository.findById(early.getId()).get().getReservedQuantity()).isEqualTo(30f);
        assertThat(stockEntryRepository.findById(late.getId()).get().getReservedQuantity()).isEqualTo(10f);
        assertThat(stockEntryRepository.findById(early.getId()).get().getQuantity()).isEqualTo(30f);

        service.confirm(res.getReservationId(), ORG, USER);

        assertThat(stockEntryRepository.findById(early.getId()).get().getQuantity()).isEqualTo(0f);
        assertThat(stockEntryRepository.findById(late.getId()).get().getQuantity()).isEqualTo(40f);
        assertThat(stockEntryRepository.findById(early.getId()).get().getReservedQuantity()).isEqualTo(0f);
        assertThat(stockLevelRepository.findByProductScoped(PRODUCT, ORG, USER).get().getCurrentStock()).isEqualTo(40f);
    }

    @Test
    void release_returns_the_hold_without_decrementing() {
        StockEntry b = batch(30f, SOON);
        stockLevel(30f);

        StockReservationResponse res = service.reserve(request("k2", 20f), ORG, USER);
        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(20f);

        service.release(res.getReservationId(), ORG, USER);

        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(0f);
        assertThat(stockEntryRepository.findById(b.getId()).get().getQuantity()).isEqualTo(30f); // unchanged
    }

    @Test
    void reserve_returns_out_of_stock_and_holds_nothing_when_insufficient() {
        StockEntry b = batch(5f, SOON);
        stockLevel(5f);

        StockReservationResponse res = service.reserve(request("k3", 1000f), ORG, USER);

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.OUT_OF_STOCK);
        assertThat(res.getReservationId()).isNull();
        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(0f);
    }

    @Test
    void reserve_is_idempotent_on_the_key() {
        StockEntry b = batch(30f, SOON);
        stockLevel(30f);

        StockReservationResponse first = service.reserve(request("same-key", 10f), ORG, USER);
        StockReservationResponse second = service.reserve(request("same-key", 10f), ORG, USER);

        assertThat(second.getReservationId()).isEqualTo(first.getReservationId());
        // Held only once despite two reserve calls.
        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(10f);
    }

    // ── G1: expired-stock exclusion (pharmacy compliance) ─────────────────────────────────────────
    @Test
    void reserve_reports_out_of_stock_when_only_expired_stock_remains() {
        StockEntry expired = batch(100f, EXPIRED);   // the only stock is already expired
        stockLevel(100f);

        StockReservationResponse res = service.reserve(request("g1a", 1f), ORG, USER);

        // never allocate expired stock — it's treated as unavailable.
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.OUT_OF_STOCK);
        assertThat(stockEntryRepository.findById(expired.getId()).get().getReservedQuantity()).isEqualTo(0f);
    }

    @Test
    void reserve_allocates_only_the_non_expired_batch_and_skips_the_expired_one() {
        StockEntry expired = batch(40f, EXPIRED);    // earliest date but expired -> must be skipped
        StockEntry fresh = batch(40f, LATER);
        stockLevel(80f);

        StockReservationResponse res = service.reserve(request("g1b", 30f), ORG, USER);

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(stockEntryRepository.findById(expired.getId()).get().getReservedQuantity()).isEqualTo(0f);  // untouched
        assertThat(stockEntryRepository.findById(fresh.getId()).get().getReservedQuantity()).isEqualTo(30f);   // from fresh
    }

    // ── G2: returns -> inventory (inverse saga, slice 34) ─────────────────────────────────────────
    private String confirmedReservation(String key, float qty) {
        StockReservationResponse res = service.reserve(request(key, qty), ORG, USER);
        service.confirm(res.getReservationId(), ORG, USER);
        return res.getReservationId();
    }

    private List<StockReturnLine> returnLines(float qty) {
        return List.of(new StockReturnLine(PRODUCT, qty));
    }

    @Test
    void return_restores_the_exact_original_batch_and_bumps_stock_level() {
        StockEntry b = batch(30f, SOON);
        stockLevel(30f);
        String resId = confirmedReservation("r1", 20f);   // confirm -> batch qty 10, level 10
        assertThat(stockEntryRepository.findById(b.getId()).get().getQuantity()).isEqualTo(10f);
        assertThat(stockLevelRepository.findByProductScoped(PRODUCT, ORG, USER).get().getCurrentStock()).isEqualTo(10f);

        service.returnPicks(resId, returnLines(20f), ORG, USER);

        // reverses the confirm exactly: the same batch is restored, real expiry retained.
        assertThat(stockEntryRepository.findById(b.getId()).get().getQuantity()).isEqualTo(30f);
        assertThat(stockEntryRepository.findById(b.getId()).get().getExpiryDate()).isEqualTo(SOON);
        assertThat(stockLevelRepository.findByProductScoped(PRODUCT, ORG, USER).get().getCurrentStock()).isEqualTo(30f);
    }

    @Test
    void repeated_partial_returns_never_over_restore_the_original_batch() {
        StockEntry b = batch(30f, SOON);
        stockLevel(30f);
        String resId = confirmedReservation("r2", 20f);   // sold 20 from this batch

        service.returnPicks(resId, returnLines(12f), ORG, USER);   // batch: 10 -> 22
        service.returnPicks(resId, returnLines(12f), ORG, USER);   // only 8 of pick room left -> batch capped at 30

        // the original batch is restored by at most what was picked from it (20); the excess 4 went to a fresh batch.
        assertThat(stockEntryRepository.findById(b.getId()).get().getQuantity()).isEqualTo(30f);
        assertThat(stockEntryRepository.count()).isEqualTo(2L);    // original + one fallback batch
    }

    @Test
    void return_with_no_reservation_falls_back_to_a_fresh_batch_and_makes_the_level_whole() {
        // no reserve/confirm, no prior stock level for the product (legacy/non-saga return path).
        service.returnPicks("does-not-exist", returnLines(10f), ORG, USER);

        assertThat(stockEntryRepository.count()).isEqualTo(1L);    // a fallback batch was created
        assertThat(stockEntryRepository.findAll().get(0).getQuantity()).isEqualTo(10f);
        assertThat(stockLevelRepository.findByProductScoped(PRODUCT, ORG, USER).get().getCurrentStock()).isEqualTo(10f);
    }
}

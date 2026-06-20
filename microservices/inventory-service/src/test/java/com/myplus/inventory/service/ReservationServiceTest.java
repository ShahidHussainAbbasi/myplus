package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
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
        StockEntry early = batch(30f, LocalDate.of(2026, 1, 1));
        StockEntry late = batch(50f, LocalDate.of(2026, 6, 1));
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
        StockEntry b = batch(30f, LocalDate.of(2026, 1, 1));
        stockLevel(30f);

        StockReservationResponse res = service.reserve(request("k2", 20f), ORG, USER);
        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(20f);

        service.release(res.getReservationId(), ORG, USER);

        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(0f);
        assertThat(stockEntryRepository.findById(b.getId()).get().getQuantity()).isEqualTo(30f); // unchanged
    }

    @Test
    void reserve_returns_out_of_stock_and_holds_nothing_when_insufficient() {
        StockEntry b = batch(5f, LocalDate.of(2026, 1, 1));
        stockLevel(5f);

        StockReservationResponse res = service.reserve(request("k3", 1000f), ORG, USER);

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.OUT_OF_STOCK);
        assertThat(res.getReservationId()).isNull();
        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(0f);
    }

    @Test
    void reserve_is_idempotent_on_the_key() {
        StockEntry b = batch(30f, LocalDate.of(2026, 1, 1));
        stockLevel(30f);

        StockReservationResponse first = service.reserve(request("same-key", 10f), ORG, USER);
        StockReservationResponse second = service.reserve(request("same-key", 10f), ORG, USER);

        assertThat(second.getReservationId()).isEqualTo(first.getReservationId());
        // Held only once despite two reserve calls.
        assertThat(stockEntryRepository.findById(b.getId()).get().getReservedQuantity()).isEqualTo(10f);
    }
}

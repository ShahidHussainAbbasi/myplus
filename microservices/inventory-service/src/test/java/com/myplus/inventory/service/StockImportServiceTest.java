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
        int created = service.importStock(List.of(StockImportLine.builder()
                .productId(10L).quantity(25f).batchNo("B1").expiryDate(LocalDate.of(2026, 12, 1))
                .purchasePrice(new BigDecimal("5.00")).costPrice(new BigDecimal("5.00"))
                .build()), ORG, USER);

        assertThat(created).isEqualTo(1);
        var level = stockLevelRepository.findByProductScoped(10L, ORG, USER);
        assertThat(level).isPresent();
        assertThat(level.get().getCurrentStock()).isEqualTo(25f);
        assertThat(level.get().getCostPrice()).isEqualByComparingTo("5.00");
        assertThat(stockEntryRepository.findAll()).singleElement()
                .satisfies(e -> {
                    assertThat(e.getProductId()).isEqualTo(10L);
                    assertThat(e.getQuantity()).isEqualTo(25f);
                    assertThat(e.getBatchNo()).isEqualTo("B1");
                });
    }
}

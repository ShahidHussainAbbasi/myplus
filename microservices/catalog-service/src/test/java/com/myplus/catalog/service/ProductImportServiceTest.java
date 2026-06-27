package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;

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
 * Slice 33, U2 — bulk import against real MySQL: category find-or-create, sku dedup within a batch,
 * blank-sku fallback, and idempotent re-import (no duplicates). Skips without Docker; run via {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductImportServiceTest {

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

    @Autowired private ProductImportService service;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @BeforeEach
    void clean() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private ProductImportLine item(Long clientRef, String sku, String name, String category) {
        return ProductImportLine.builder().clientRef(clientRef).sku(sku).name(name).categoryName(category).build();
    }

    @Test
    void imports_products_and_resolves_category_find_or_create() {
        List<ProductImportResult> map = service.importProducts(List.of(
                item(1L, "A1", "Aspirin", "Medicine"),
                item(2L, "B1", "Bandage", "Medicine")), ORG, USER);

        assertThat(map).hasSize(2);
        assertThat(map).allSatisfy(r -> assertThat(r.getProductId()).isNotNull());
        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(categoryRepository.count()).as("category created once and reused").isEqualTo(1);
    }

    @Test
    void dedups_clashing_skus_within_a_batch() {
        service.importProducts(List.of(
                item(1L, "COLA", "Cola 500ml", null),
                item(2L, "COLA", "Cola 1L", null)), ORG, USER);

        assertThat(productRepository.findAll()).extracting("sku")
                .containsExactlyInAnyOrder("COLA", "COLA-2");
    }

    @Test
    void falls_back_to_item_id_when_sku_blank() {
        service.importProducts(List.of(item(99L, "  ", "No-code item", null)), ORG, USER);

        assertThat(productRepository.findBySkuScoped("ITEM-99", ORG, USER)).isPresent();
    }

    @Test
    void falls_back_name_to_sku_when_source_name_is_null() {
        service.importProducts(List.of(item(7L, "SKU7", null, null)), ORG, USER);

        assertThat(productRepository.findBySkuScoped("SKU7", ORG, USER))
                .get().extracting("name").isEqualTo("SKU7");
    }

    @Test
    void reimport_is_idempotent_and_creates_no_duplicate() {
        ProductImportResult first = service.importProducts(List.of(item(5L, "SKU5", "Item5", null)), ORG, USER).get(0);
        ProductImportResult again = service.importProducts(List.of(item(5L, "SKU5", "Item5", null)), ORG, USER).get(0);

        assertThat(again.getProductId()).isEqualTo(first.getProductId());
        assertThat(productRepository.count()).isEqualTo(1);
    }
}

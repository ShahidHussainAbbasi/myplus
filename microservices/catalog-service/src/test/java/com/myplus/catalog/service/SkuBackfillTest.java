package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.OrgDocumentSeqRepo;
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
 * SKU-AUTO-2 — the deploy fill: every product with no SKU gets its business's next 12-digit code, oldest first; a typed
 * SKU and a legacy no-organisation row are left alone; running it again changes nothing.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SkuBackfillTest {

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

    @Autowired private SkuBackfill backfill;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @Autowired private OrgDocumentSeqRepo counters;

    private static final Long A = 501L, B = 502L, USER = 9L;

    @BeforeEach
    void clean() {
        products.deleteAll();
        categories.deleteAll();
        counters.deleteAll();
    }

    private Long save(String name, String sku, Long org) {
        return products.saveAndFlush(Product.builder().name(name).sku(sku).organizationId(org).userId(USER).build()).getId();
    }

    private String skuOf(Long id) {
        return products.findById(id).orElseThrow().getSku();
    }

    @Test
    void fills_every_blank_sku_per_business_oldest_first_and_leaves_the_rest_alone() {
        Long a1 = save("a1", null, A), a2 = save("a2", null, A), typed = save("t", "MY-1", A);
        Long b1 = save("b1", null, B), legacy = save("old", null, null);

        assertThat(backfill.run()).isEqualTo(3);

        assertThat(skuOf(a1)).isEqualTo("200000000001");
        assertThat(skuOf(a2)).isEqualTo("200000000002");
        assertThat(skuOf(b1)).as("each business has its own counter").isEqualTo("200000000001");
        assertThat(skuOf(typed)).as("a typed SKU is never touched").isEqualTo("MY-1");
        assertThat(skuOf(legacy)).as("no organisation, no counter - left blank, logged").isNull();
    }

    @Test
    void running_it_again_changes_nothing() {
        Long a1 = save("a1", null, A);
        backfill.run();
        String first = skuOf(a1);

        assertThat(backfill.run()).as("idempotent: nothing left to fill").isZero();
        assertThat(skuOf(a1)).isEqualTo(first);
    }

    @Test
    void a_code_already_typed_by_hand_is_skipped() {
        save("typed", "200000000001", A);
        Long a1 = save("a1", null, A);
        backfill.run();
        assertThat(skuOf(a1)).isEqualTo("200000000002");
    }
}

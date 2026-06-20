package com.myplus.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.catalog.entity.Product;

/**
 * Slice 33, Phase 5a — proves catalog Product org-scoping: {@code findScoped} returns the caller's org rows
 * plus their pre-migration org-NULL rows and never another tenant's; {@code existsBySkuScoped} is per-org
 * (the fix for the old global-unique-SKU bug). Skips (does not fail) without Docker; run via {@code mvn test}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductRepoScopingTest {

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

    @Autowired
    private ProductRepository repo;
    @Autowired
    private TestEntityManager em;

    private Product persist(String sku, String name, Long org, Long user) {
        Product p = Product.builder().sku(sku).name(name).organizationId(org).userId(user).build();
        return em.persistAndFlush(p);
    }

    @Test
    void findScoped_returns_own_org_plus_callers_null_org_rows_only() {
        Product p1 = persist("SKU1", "Org1-User1", 1L, 1L);
        Product p2 = persist("SKU2", "Org1-User2", 1L, 2L);   // same org, other user — visible
        Product p3 = persist("SKU3", "Org2-User3", 2L, 3L);   // another tenant — must NOT appear
        Product p4 = persist("SKU4", "Null-User1", null, 1L); // pre-migration, caller's — fallback
        Product p5 = persist("SKU5", "Null-User2", null, 2L); // pre-migration, other user — must NOT appear

        var page = repo.findScoped(1L, 1L, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Product::getId)
                .containsExactlyInAnyOrder(p1.getId(), p2.getId(), p4.getId())
                .doesNotContain(p3.getId(), p5.getId());
    }

    @Test
    void existsBySkuScoped_is_per_tenant() {
        persist("DUP", "Org1", 1L, 1L);

        assertThat(repo.existsBySkuScoped("DUP", 1L, 1L)).isTrue();   // same org -> duplicate
        assertThat(repo.existsBySkuScoped("DUP", 2L, 9L)).isFalse();  // another org -> SKU is free
    }
}

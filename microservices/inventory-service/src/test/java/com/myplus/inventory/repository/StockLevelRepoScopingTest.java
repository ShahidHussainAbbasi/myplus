package com.myplus.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.myplus.inventory.entity.StockLevel;

/**
 * Slice 33, Phase 5b — proves inventory StockLevel org-scoping: {@code findScoped} isolates tenants and
 * {@code findByProductScoped} resolves the caller's level only. Skips without Docker; run via {@code mvn test}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class StockLevelRepoScopingTest {

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
    private StockLevelRepository repo;
    @Autowired
    private TestEntityManager em;

    private StockLevel persist(Long productId, Float qty, Long org, Long user) {
        StockLevel sl = StockLevel.builder().productId(productId).currentStock(qty)
                .organizationId(org).userId(user).build();
        return em.persistAndFlush(sl);
    }

    @Test
    void findScoped_isolates_tenants_with_null_fallback() {
        StockLevel s1 = persist(10L, 5f, 1L, 1L);
        StockLevel s2 = persist(11L, 5f, 1L, 2L);   // same org, other user — visible
        StockLevel s3 = persist(12L, 5f, 2L, 3L);   // another tenant — must NOT appear
        StockLevel s4 = persist(13L, 5f, null, 1L); // pre-migration, caller's — fallback

        assertThat(repo.findScoped(1L, 1L)).extracting(StockLevel::getId)
                .containsExactlyInAnyOrder(s1.getId(), s2.getId(), s4.getId())
                .doesNotContain(s3.getId());
    }

    @Test
    void findByProductScoped_returns_only_callers_level() {
        persist(100L, 7f, 1L, 1L);
        persist(100L, 99f, 2L, 3L);   // another tenant's level for the same product id

        assertThat(repo.findByProductScoped(100L, 1L, 1L))
                .isPresent()
                .get().extracting(StockLevel::getCurrentStock).isEqualTo(7f);
    }
}

package com.myplus.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.market.entity.MarketPolicy;
import com.myplus.market.repository.MarketPolicyRepo;

/**
 * MP-0b — STANDARDS D2: V1 runs on an EMPTY MySQL and the service boots under ddl-auto=validate, so an entity
 * column that disagrees with the migration fails HERE, not as a crash loop on deploy. Also proves the database
 * (not only the service) holds "one published version per policy type".
 *
 * <p>Skips without Docker — read the Skipped count (D2a).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class MarketFlywayMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
        r.add("audit.outbox.relay-delay-ms", () -> "3600000");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MarketPolicyRepo policies;

    @Test
    void v1_applies_and_the_entities_validate_against_it() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
        for (String t : new String[] {"market_policy", "seller_profile", "seller_agreement", "audit_outbox"}) {
            String engine = jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, t);
            assertThat(engine).as(t + " is InnoDB (row locks, FKs)").isEqualTo("InnoDB");
        }
        Integer enums = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND DATA_TYPE IN ('enum','text','longtext')", Integer.class);
        assertThat(enums).as("no ENUM / TEXT columns (STANDARDS §0)").isZero();
    }

    @Test
    void the_database_refuses_a_second_published_version_of_a_type() {
        policies.saveAndFlush(policy(900L, 1, "PUBLISHED", "COMMISSION"));
        policies.saveAndFlush(policy(900L, 2, "DRAFT", null));
        assertThatThrownBy(() -> policies.saveAndFlush(policy(900L, 3, "PUBLISHED", "COMMISSION")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // the same version number twice is refused too
        assertThatThrownBy(() -> policies.saveAndFlush(policy(900L, 2, "DRAFT", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(policies.maxVersion(900L, "COMMISSION")).isEqualTo(2);
    }

    private static MarketPolicy policy(Long org, int v, String status, String slot) {
        MarketPolicy p = new MarketPolicy();
        p.setOrganizationId(org);
        p.setPolicyType("COMMISSION");
        p.setVersionNo(v);
        p.setTitle("Commission v" + v);
        p.setSummary("10% of the line, minimum Rs. 20");
        p.setStatus(status);
        p.setPublishedSlot(slot);
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }
}

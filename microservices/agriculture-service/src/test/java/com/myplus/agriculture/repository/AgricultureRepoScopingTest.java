package com.myplus.agriculture.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

import com.myplus.agriculture.entity.AgricultureIncome;
import com.myplus.agriculture.entity.Land;

/**
 * Tech-debt #12 / #18 — agriculture org-scoping under test (real MySQL via Testcontainers).
 * Income/Land findScoped return the caller's org rows (+ pre-migration org-NULL) and exclude other
 * tenants'. Skips when Docker is absent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AgricultureRepoScopingTest {

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

    @Autowired
    private AgricultureIncomeRepo incomeRepo;
    @Autowired
    private LandRepo landRepo;
    @Autowired
    private TestEntityManager em;

    private AgricultureIncome income(Long org, Long user) {
        AgricultureIncome i = new AgricultureIncome(user); // 1-arg ctor sets userId
        i.setOrganizationId(org);
        return em.persistAndFlush(i);
    }

    private Land land(String name, Long org, Long user) {
        Land l = new Land();
        l.setLandName(name);
        l.setUserId(user);
        l.setOrganizationId(org);
        return em.persistAndFlush(l);
    }

    @Test
    void income_findScoped_isolates_by_org() {
        AgricultureIncome a = income(1L, 1L);
        AgricultureIncome b = income(1L, 2L);
        AgricultureIncome other = income(2L, 3L);

        List<AgricultureIncome> scoped = incomeRepo.findScoped(1L, 1L);

        assertThat(scoped).extracting(AgricultureIncome::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId())
                .doesNotContain(other.getId());
    }

    @Test
    void land_findScoped_isolates_by_org() {
        Land mine = land("Field-1", 1L, 1L);
        Land other = land("Field-2", 2L, 3L);

        List<Land> scoped = landRepo.findScoped(1L, 1L);

        assertThat(scoped).extracting(Land::getId).contains(mine.getId()).doesNotContain(other.getId());
    }
}

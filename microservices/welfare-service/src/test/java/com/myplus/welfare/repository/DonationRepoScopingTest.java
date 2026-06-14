package com.myplus.welfare.repository;

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

import com.myplus.welfare.entity.Donation;
import com.myplus.welfare.entity.Donator;

/**
 * Tech-debt #12 / #18 — welfare org-scoping under test (real MySQL via Testcontainers).
 * Donator/Donation findScoped return the caller's org rows + their pre-migration org-NULL rows,
 * and never another tenant's. Skips when Docker is absent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class DonationRepoScopingTest {

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
    private DonationRepo donationRepo;
    @Autowired
    private DonatorRepo donatorRepo;
    @Autowired
    private TestEntityManager em;

    private Donator donator(String name, Long org, Long user) {
        Donator d = new Donator();
        d.setName(name);
        d.setOrganizationId(org);
        d.setUserId(user);
        return em.persistAndFlush(d);
    }

    private Donation donation(Donator d, Long org, Long user) {
        Donation x = new Donation();
        x.setDonator(d);          // @ManyToOne(optional=false) — required
        x.setOrganizationId(org);
        x.setUserId(user);
        x.setAmount(10f);
        return em.persistAndFlush(x);
    }

    @Test
    void donator_findScoped_isolates_by_org_with_null_fallback() {
        Donator a = donator("A", 1L, 1L);
        Donator b = donator("B", 1L, 2L);
        Donator other = donator("C", 2L, 3L);
        Donator mineNull = donator("D", null, 1L);
        Donator othersNull = donator("E", null, 2L);

        List<Donator> scoped = donatorRepo.findScoped(1L, 1L);

        assertThat(scoped).extracting(Donator::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId(), mineNull.getId())
                .doesNotContain(other.getId(), othersNull.getId());
    }

    @Test
    void donation_findScoped_isolates_by_org() {
        Donation own = donation(donator("X", 1L, 1L), 1L, 1L);
        Donation other = donation(donator("Y", 2L, 3L), 2L, 3L);

        List<Donation> scoped = donationRepo.findScoped(1L, 1L);

        assertThat(scoped).extracting(Donation::getId).contains(own.getId()).doesNotContain(other.getId());
    }
}

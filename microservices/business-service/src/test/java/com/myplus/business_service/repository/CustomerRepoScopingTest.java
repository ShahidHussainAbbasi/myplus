package com.myplus.business_service.repository;

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

import com.myplus.business_service.entity.Customer;

/**
 * Tech-debt #12 — first Testcontainers test (real MySQL, not H2). Proves the multi-tenant
 * {@code findScoped} contract: a caller sees their own org's rows plus their pre-migration
 * org-NULL rows, and never another tenant's. This is the template other repo/slice tests follow.
 *
 * Gated with {@code disabledWithoutDocker = true}: it skips (does not fail) when Docker is absent,
 * so it never breaks a Docker-less build. Run with Docker available via {@code mvn test}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class CustomerRepoScopingTest {

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
    private CustomerRepo repo;
    @Autowired
    private TestEntityManager em;

    private Customer persist(String name, String contact, Long org, Long user) {
        Customer c = new Customer();
        c.setName(name);
        c.setContact(contact);
        c.setOrganizationId(org);
        c.setUserId(user);
        return em.persistAndFlush(c);
    }

    @Test
    void findScoped_returns_own_org_plus_callers_null_org_rows_only() {
        Customer c1 = persist("Org1-User1", "111", 1L, 1L);   // own org
        Customer c2 = persist("Org1-User2", "222", 1L, 2L);   // own org, different user — still visible
        Customer c3 = persist("Org2-User3", "333", 2L, 3L);   // ANOTHER tenant — must NOT appear
        Customer c4 = persist("NullOrg-User1", "444", null, 1L); // pre-migration, caller's — fallback
        Customer c5 = persist("NullOrg-User2", "555", null, 2L); // pre-migration, other user — must NOT appear

        List<Customer> scoped = repo.findScoped(1L, 1L);

        assertThat(scoped).extracting(Customer::getCustomerId)
                .containsExactlyInAnyOrder(c1.getCustomerId(), c2.getCustomerId(), c4.getCustomerId())
                .doesNotContain(c3.getCustomerId(), c5.getCustomerId());
    }
}

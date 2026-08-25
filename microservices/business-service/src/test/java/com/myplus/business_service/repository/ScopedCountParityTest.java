package com.myplus.business_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
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
 * PERF — the dashboard's counts moved into SQL. Do they still count the same rows?
 *
 * <h3>The risk this exists for</h3>
 * {@code findScoped(...).size()} became {@code countScoped(...)}. The danger is not that the new query fails
 * — it is that it succeeds with a slightly different predicate. A count that quietly dropped the NULL-org
 * fallback, or reached past the tenant, returns a plausible integer onto a dashboard nobody would think to
 * verify. There is no screen on which "1,388" looks wrong.
 *
 * <p>So every case asserts the same fact twice — once through the list that was trusted before, once through
 * the count that replaced it — and requires them to agree. A rewrite that changes the answer fails here,
 * rather than in six months when a figure is finally questioned.
 *
 * <p>Real MySQL through Testcontainers, on the same harness as {@link CustomerRepoScopingTest}: the fallback
 * is a SQL behaviour ({@code IS NULL} inside an {@code OR}), and an in-memory stand-in would not prove it.
 * {@code disabledWithoutDocker} so a Docker-less build skips rather than breaks.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ScopedCountParityTest {

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

    @Autowired private CustomerRepo repo;
    @Autowired private TestEntityManager em;

    private Customer persist(String name, Long org, Long user) {
        Customer c = new Customer();
        c.setName(name);
        c.setContact("0300" + Math.abs(name.hashCode() % 1000000));
        c.setOrganizationId(org);
        c.setUserId(user);
        return em.persistAndFlush(c);
    }

    /** The exact population {@link CustomerRepoScopingTest} proves findScoped against. */
    private void seedTheScopingFixture() {
        persist("Org1-User1", 1L, 1L);      // own org
        persist("Org1-User2", 1L, 2L);      // own org, other user — visible
        persist("Org2-User3", 2L, 3L);      // ANOTHER tenant — must not count
        persist("NullOrg-User1", null, 1L); // pre-migration, caller's — the fallback
        persist("NullOrg-User2", null, 2L); // pre-migration, other user — must not count
    }

    // ── the parity that carries the change ──────────────────────────────────────────────────────

    @Test
    @DisplayName("THE CASE — countScoped equals findScoped().size() over a mixed population")
    void count_matches_the_list_it_replaced() {
        seedTheScopingFixture();

        assertThat(repo.countScoped(1L, 1L))
                .as("the count and the list must agree — the dashboard swapped one for the other")
                .isEqualTo(repo.findScoped(1L, 1L).size());
    }

    @Test
    @DisplayName("…and the number is the RIGHT one: 3 of the 5 rows belong to this caller")
    void count_is_the_expected_value_not_merely_consistent() {
        /*
         * Parity alone would be satisfied if BOTH sides were wrong in the same direction — say, if each
         * counted every row on the instance. Pinning the literal makes that impossible: of the five rows
         * seeded, exactly three are this caller's (two in their org, one pre-migration row they created).
         */
        seedTheScopingFixture();
        assertThat(repo.countScoped(1L, 1L)).isEqualTo(3);
    }

    @Test
    @DisplayName("POSITIVE CONTROL — the count moves when a row is added")
    void the_count_is_not_frozen() {
        // Without this, a countScoped that always answered the same number would satisfy the parity case
        // whenever findScoped happened to agree. A number that never changes is the easiest wrong answer
        // to ship, and the hardest to notice.
        long before = repo.countScoped(1L, 1L);
        persist("Mover", 1L, 1L);
        assertThat(repo.countScoped(1L, 1L)).isEqualTo(before + 1);
    }

    // ── the two halves of the predicate, separately ─────────────────────────────────────────────

    @Test
    @DisplayName("another tenant's rows are never counted")
    void count_is_tenant_scoped() {
        persist("Mine", 1L, 1L);
        persist("Theirs", 2L, 2L);
        assertThat(repo.countScoped(1L, 1L))
                .as("a count that grew here is a count that crosses tenants")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the NULL-org fallback survives — and does not leak between users")
    void null_org_rows_count_only_for_their_creator() {
        /*
         * The half most likely to be lost in a rewrite, because it reads like noise:
         *
         *     organizationId = :orgId OR (organizationId IS NULL AND userId = :userId)
         *
         * It exists for rows written before tenancy, which belong to whoever created them. Drop it and those
         * rows vanish from every count while the list still shows them. Broaden it to "organizationId IS
         * NULL" and every tenant inherits every legacy row on the instance. Both directions are asserted.
         */
        persist("MyLegacy", null, 1L);
        persist("TheirLegacy", null, 2L);

        assertThat(repo.countScoped(1L, 1L)).as("my own pre-migration row counts").isEqualTo(1);
        assertThat(repo.countScoped(1L, 1L)).isEqualTo(repo.findScoped(1L, 1L).size());
    }
}

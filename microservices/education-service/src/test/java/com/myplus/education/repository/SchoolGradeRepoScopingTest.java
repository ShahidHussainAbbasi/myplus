package com.myplus.education.repository;

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

import com.myplus.education.entity.Grade;
import com.myplus.education.entity.School;

/**
 * Tech-debt #12 — education org-scoping under test (real MySQL via Testcontainers).
 * School/Grade findScoped return the caller's org rows + their pre-migration org-NULL rows, never
 * another tenant's. Skips when Docker is absent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SchoolGradeRepoScopingTest {

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
    private SchoolRepository schoolRepo;
    @Autowired
    private GradeRepository gradeRepo;
    @Autowired
    private TestEntityManager em;

    private School school(String name, Long org, Long user) {
        School s = new School();
        s.setName(name);
        s.setOrganizationId(org);
        s.setUserId(user);
        return em.persistAndFlush(s);
    }

    private Grade grade(String name, Long org, Long user) {
        Grade g = new Grade();
        g.setName(name);
        g.setOrganizationId(org);
        g.setUserId(user);
        return em.persistAndFlush(g);
    }

    @Test
    void school_findScoped_isolates_by_org_with_null_fallback() {
        School a = school("S-A", 1L, 1L);
        School b = school("S-B", 1L, 2L);
        School other = school("S-C", 2L, 3L);
        School mineNull = school("S-D", null, 1L);
        School othersNull = school("S-E", null, 2L);

        List<School> scoped = schoolRepo.findScoped(1L, 1L);

        assertThat(scoped).extracting(School::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId(), mineNull.getId())
                .doesNotContain(other.getId(), othersNull.getId());
    }

    @Test
    void grade_findScoped_isolates_by_org() {
        Grade mine = grade("G1", 1L, 1L);
        Grade other = grade("G2", 2L, 3L);

        List<Grade> scoped = gradeRepo.findScoped(1L, 1L);

        assertThat(scoped).extracting(Grade::getId).contains(mine.getId()).doesNotContain(other.getId());
    }
}

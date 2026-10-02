package com.myplus.party;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.party.entity.Party;
import com.myplus.party.repository.PartyRepository;

/**
 * Standard D2 — party-service's migrations had never run in {@code mvn test} (the service had no tests at all).
 *
 * An EMPTY MySQL, schema built only by Flyway, {@code ddl-auto=validate}: booting proves the migrations apply in
 * order and produce what the entities expect, and that every JPQL query (including DR-1's duplicates query) parses.
 * Real MySQL — V4's backfill uses REGEXP_REPLACE. Skips without Docker: READ THE SKIPPED COUNT (D2a).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

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
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PartyRepository repo;

    private boolean hasIndex(String table, String index) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?", Integer.class, table, index);
        return n != null && n > 0;
    }

    @Test
    void every_migration_applies_to_an_empty_database() {
        List<String> applied = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1 "
                + "AND version IS NOT NULL ORDER BY installed_rank", String.class);
        assertThat(applied).containsExactly("1", "2", "3", "4");
    }

    @Test
    void dr1_match_key_indexes_exist() {
        assertThat(hasIndex("party", "idx_party_org_contact_key")).isTrue();
        assertThat(hasIndex("party", "idx_party_org_tax_key")).isTrue();
        assertThat(hasIndex("party_role_link", "idx_role_link_org_local")).isTrue();
        // Kept on purpose (D4/D5): the raw-text constraint predates DR-1.
        assertThat(hasIndex("party", "uq_party_org_contact")).isTrue();
    }

    @Test
    void the_v4_backfill_expression_gives_the_same_key_as_PartyKeys() {
        String sqlKey = jdbc.queryForObject(
                "SELECT RIGHT(REGEXP_REPLACE('+92 300-1234567', '[^0-9]', ''), 10)", String.class);
        assertThat(sqlKey).isEqualTo(com.myplus.common.web.PartyKeys.phoneKey("+92 300-1234567"));
    }

    @Test
    void every_write_derives_the_phone_key() {
        Party p = new Party();
        p.setOrganizationId(990001L);
        p.setName("Usman & Co");
        p.setContact("+923001234567");
        p = repo.saveAndFlush(p);
        assertThat(p.getContactKey()).isEqualTo("3001234567");

        p.setContact("0311-7654321");
        p = repo.saveAndFlush(p);
        assertThat(p.getContactKey()).as("an edit re-derives it").isEqualTo("3117654321");
    }

    @Test
    void the_duplicates_query_finds_two_partners_split_on_format() {
        Party a = new Party(); a.setOrganizationId(990002L); a.setName("A"); a.setContact("0300-5550001");
        Party b = new Party(); b.setOrganizationId(990002L); b.setName("B"); b.setContact("+923005550001");
        Party c = new Party(); c.setOrganizationId(990002L); c.setName("C"); c.setContact("03009990000");
        repo.saveAllAndFlush(List.of(a, b, c));
        assertThat(repo.findPossibleDuplicates(990002L)).extracting(Party::getName).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void a_partner_whose_phone_text_is_taken_still_carries_the_phone_key() {
        // Same number as another partner, different CNIC: the raw UNIQUE (org, contact) keeps the text off this row,
        // but the KEY must stay, or "possible duplicates" can never show the pair (DR1-6's first red run).
        Party p = new Party();
        p.setOrganizationId(990003L);
        p.setName("Second legal entity");
        p.setPhoneWithoutContact("0300-7778889");
        p = repo.saveAndFlush(p);
        assertThat(p.getContact()).isNull();
        assertThat(p.getContactKey()).isEqualTo("3007778889");

        p.setName("Renamed");                 // a later edit that does not touch the contact keeps the key
        p.setPhoneWithoutContact(null);
        p = repo.saveAndFlush(p);
        assertThat(p.getContactKey()).isEqualTo("3007778889");
    }
}

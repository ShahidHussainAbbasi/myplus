package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.myplus.pharma.dto.ClinicalDTO;
import com.myplus.pharma.dto.InteractionDTO;
import com.myplus.pharma.dto.SafetyReportDTO;
import com.myplus.pharma.repository.DrugInteractionRepository;
import com.myplus.pharma.repository.MedicineClinicalRepository;

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
 * P7 (slice 44) — dispense safety: clinical flags (controlled/rx) + interactions among the dispensed set, org-scoped.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SafetyServiceTest {

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

    private static final Long ORG = 1L, USER = 1L;

    @Autowired private SafetyService safety;
    @Autowired private MedicineClinicalRepository clinicalRepo;
    @Autowired private DrugInteractionRepository interactionRepo;

    @BeforeEach
    void clean() { interactionRepo.deleteAll(); clinicalRepo.deleteAll(); }

    private ClinicalDTO clinical(long itemId, boolean rx, boolean controlled) {
        ClinicalDTO d = new ClinicalDTO();
        d.setItemId(itemId); d.setRxRequired(rx); d.setControlledSubstance(controlled);
        return d;
    }

    @Test
    void check_reports_controlled_rx_and_interactions_for_the_set() {
        safety.upsertClinical(clinical(10L, true, true), ORG, USER);    // controlled + rx
        safety.upsertClinical(clinical(20L, false, false), ORG, USER);
        InteractionDTO inter = new InteractionDTO();
        inter.setItemId1(10L); inter.setItemId2(20L); inter.setSeverity("SEVERE"); inter.setDescription("X+Y bad");
        safety.addInteraction(inter, ORG, USER);

        SafetyReportDTO r = safety.check(List.of(10L, 20L), ORG, USER);
        assertThat(r.getControlledItems()).containsExactly(10L);
        assertThat(r.getRxRequiredItems()).containsExactly(10L);
        assertThat(r.getInteractions()).hasSize(1);
        assertThat(r.getInteractions().get(0).getSeverity()).isEqualTo("SEVERE");
        assertThat(r.hasWarnings()).isTrue();
    }

    @Test
    void interaction_only_fires_when_both_items_are_present() {
        InteractionDTO inter = new InteractionDTO();
        inter.setItemId1(10L); inter.setItemId2(20L); inter.setSeverity("MODERATE");
        safety.addInteraction(inter, ORG, USER);

        assertThat(safety.check(List.of(10L), ORG, USER).getInteractions()).isEmpty();          // only one item
        assertThat(safety.check(List.of(10L, 20L), ORG, USER).getInteractions()).hasSize(1);     // both present
    }

    @Test
    void clinical_flags_are_org_scoped() {
        safety.upsertClinical(clinical(10L, true, true), ORG, USER);
        assertThat(safety.check(List.of(10L), ORG, USER).getControlledItems()).containsExactly(10L);
        assertThat(safety.check(List.of(10L), 999L, 999L).getControlledItems()).isEmpty();   // other tenant
    }
}

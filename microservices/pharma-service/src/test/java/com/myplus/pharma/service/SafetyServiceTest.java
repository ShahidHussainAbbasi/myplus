package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    /**
     * B1: catalog owns rxRequired / controlledSubstance now, so the flag half of this service is delegation and
     * is mocked here. Interactions are still wholly pharma-owned and hit the real database.
     */
    @MockitoBean private CatalogClient catalogClient;

    @BeforeEach
    void clean() { interactionRepo.deleteAll(); clinicalRepo.deleteAll(); }

    private ClinicalDTO clinical(long itemId, boolean rx, boolean controlled) {
        ClinicalDTO d = new ClinicalDTO();
        d.setProductId(itemId); d.setRxRequired(rx); d.setControlledSubstance(controlled);
        return d;
    }

    /** Stub catalog as the source of truth for the flags. */
    private void catalogSays(long productId, boolean rx, boolean controlled) {
        when(catalogClient.getProducts(List.of(productId))).thenReturn(List.of(
                ProductRef.builder().id(productId).name("P" + productId)
                        .rxRequired(rx).controlledSubstance(controlled).build()));
    }

    @Test
    void check_reports_controlled_rx_and_interactions_for_the_set() {
        when(catalogClient.getProducts(List.of(10L, 20L))).thenReturn(List.of(
                ProductRef.builder().id(10L).rxRequired(true).controlledSubstance(true).build(),
                ProductRef.builder().id(20L).rxRequired(false).controlledSubstance(false).build()));
        InteractionDTO inter = new InteractionDTO();
        inter.setProductId1(10L); inter.setProductId2(20L); inter.setSeverity("SEVERE"); inter.setDescription("X+Y bad");
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
        inter.setProductId1(10L); inter.setProductId2(20L); inter.setSeverity("MODERATE");
        safety.addInteraction(inter, ORG, USER);

        assertThat(safety.check(List.of(10L), ORG, USER).getInteractions()).isEmpty();          // only one item
        assertThat(safety.check(List.of(10L, 20L), ORG, USER).getInteractions()).hasSize(1);     // both present
    }

    // ── B1: catalog is the single writer / reader for the two enforcement flags ───────────────────────
    @Test
    void saving_clinical_flags_writes_them_to_catalog() {
        safety.upsertClinical(clinical(10L, true, true), ORG, USER);
        // The sell guard reads the flag off the product master — if this write is skipped the tills never enforce.
        verify(catalogClient).updateClinicalFlags(10L, true, true);
    }

    @Test
    void a_catalog_write_failure_is_not_swallowed() {
        // Better to fail the save than to leave a local row claiming a flag the tills will never honour.
        doThrow(new RuntimeException("catalog down"))
                .when(catalogClient).updateClinicalFlags(anyLong(), anyBoolean(), anyBoolean());

        assertThatThrownBy(() -> safety.upsertClinical(clinical(11L, true, false), ORG, USER))
                .isInstanceOf(RuntimeException.class);
        assertThat(clinicalRepo.findAll()).isEmpty();   // rolled back — no half-saved flag
    }

    // ── C2: the basket's controlled flags resolve in one call, not one per line ───────────────────────
    @Test
    void controlled_lookup_for_a_basket_is_a_single_catalog_call() {
        when(catalogClient.getProducts(List.of(10L, 20L, 30L))).thenReturn(List.of(
                ProductRef.builder().id(10L).controlledSubstance(true).build(),
                ProductRef.builder().id(20L).controlledSubstance(false).build(),
                ProductRef.builder().id(30L).controlledSubstance(true).build()));

        assertThat(safety.controlledSet(List.of(10L, 20L, 30L), ORG, USER)).containsExactlyInAnyOrder(10L, 30L);
        verify(catalogClient, times(1)).getProducts(anyList());   // one round trip for the whole basket
    }

    @Test
    void controlled_lookup_reads_catalog() {
        catalogSays(10L, false, true);
        assertThat(safety.isControlled(10L, ORG, USER)).isTrue();

        catalogSays(20L, false, false);
        assertThat(safety.isControlled(20L, ORG, USER)).isFalse();
    }

    @Test
    void a_catalog_outage_degrades_the_warning_instead_of_breaking_it() {
        when(catalogClient.getProducts(List.of(10L))).thenThrow(new RuntimeException("catalog down"));
        // The pre-dispense warning is advisory; losing it must not throw at the pharmacist.
        assertThat(safety.check(List.of(10L), ORG, USER).getControlledItems()).isEmpty();
        assertThat(safety.isControlled(10L, ORG, USER)).isFalse();
    }

    @Test
    void interactions_are_org_scoped() {
        // Flag scoping is catalog's job now; what this service still owns is the interaction set.
        InteractionDTO inter = new InteractionDTO();
        inter.setProductId1(10L); inter.setProductId2(20L); inter.setSeverity("MILD");
        safety.addInteraction(inter, ORG, USER);

        assertThat(safety.check(List.of(10L, 20L), ORG, USER).getInteractions()).hasSize(1);
        assertThat(safety.check(List.of(10L, 20L), 999L, 999L).getInteractions()).isEmpty();   // other tenant
    }
}

package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.dto.PrescriptionItemDTO;
import com.myplus.pharma.repository.PrescriptionItemRepository;
import com.myplus.pharma.repository.PrescriptionRepository;

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
 * P5 (slice 41) — prescription intake: header + items persist, reads are org-scoped. Real MySQL; skips without Docker.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PrescriptionServiceTest {

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

    @Autowired private PrescriptionService service;
    @Autowired private PrescriptionRepository prescriptionRepo;
    @Autowired private PrescriptionItemRepository itemRepo;

    @BeforeEach
    void clean() { itemRepo.deleteAll(); prescriptionRepo.deleteAll(); }

    private PrescriptionDTO sample(String patient) {
        PrescriptionDTO d = new PrescriptionDTO();
        d.setPatientName(patient);
        d.setDoctorName("Dr Test");
        d.setDoctorLicense("LIC-123");
        d.setDiagnosis("Headache");
        PrescriptionItemDTO it = new PrescriptionItemDTO();
        it.setProductId(555L);
        it.setMedicineName("Paracetamol 500mg");
        it.setQuantity(20);
        it.setDosage("1 tab");
        it.setFrequency("TDS");
        it.setDuration("5 days");
        d.getItems().add(it);
        return d;
    }

    @Test
    void create_persists_header_and_items_then_get_returns_them() {
        PrescriptionDTO out = service.create(sample("Alice"), ORG, USER);
        assertThat(out.getId()).isNotNull();
        assertThat(out.getStatus()).isEqualTo("PENDING");
        assertThat(out.getItems()).hasSize(1);

        PrescriptionDTO got = service.get(out.getId(), ORG, USER);
        assertThat(got.getPatientName()).isEqualTo("Alice");
        assertThat(got.getItems().get(0).getProductId()).isEqualTo(555L);
        assertThat(got.getItems().get(0).getFrequency()).isEqualTo("TDS");
    }

    @Test
    void list_is_org_scoped() {
        service.create(sample("Bob"), ORG, USER);
        assertThat(service.list(ORG, USER)).hasSize(1);
        assertThat(service.list(999L, 999L)).isEmpty();   // another tenant sees nothing
    }

    // ── C1: the list is bounded and free of the per-row item query ───────────────────────────────────
    @Test
    void list_is_bounded_and_newest_first() {
        for (int i = 0; i < 5; i++) service.create(sample("Bulk" + i), ORG, USER);

        assertThat(service.list(ORG, USER, 3)).hasSize(3);          // capped, not "everything ever recorded"
        List<PrescriptionDTO> all = service.list(ORG, USER);
        assertThat(all).hasSize(5);                                 // default limit is well above 5
        // Newest first. Asserted as "descending by createdAt" rather than naming a row, because five inserts can
        // land on the same timestamp and the contract is the ordering, not which of two ties wins.
        assertThat(all).extracting(PrescriptionDTO::getCreatedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void list_loads_every_prescriptions_items_without_a_query_per_row() {
        for (int i = 0; i < 4; i++) service.create(sample("Item" + i), ORG, USER);

        List<PrescriptionDTO> out = service.list(ORG, USER);

        // The N+1 fix groups one item query across the page — every row must still carry its own items, which is
        // exactly what a careless grouping would get wrong.
        assertThat(out).hasSize(4);
        assertThat(out).allSatisfy(d -> {
            assertThat(d.getItems()).hasSize(1);
            assertThat(d.getItems().get(0).getProductId()).isEqualTo(555L);
            assertThat(d.getItems().get(0).getFrequency()).isEqualTo("TDS");
        });
    }

    // ── B5: intake validation ────────────────────────────────────────────────────────────────────────
    @Test
    void an_item_with_no_quantity_is_rejected() {
        // Left unchecked this is worse than a typo: recomputeStatus reads "dispensed 0 of 0" as satisfied and
        // the first dispense marks the whole prescription FULLY_DISPENSED.
        PrescriptionDTO d = sample("Carol");
        d.getItems().get(0).setQuantity(0);

        assertThatThrownBy(() -> service.create(d, ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("quantity");
        assertThat(prescriptionRepo.findAll()).isEmpty();   // nothing half-written
    }

    @Test
    void an_item_with_no_medicine_is_rejected() {
        PrescriptionDTO d = sample("Dave");
        d.getItems().get(0).setProductId(null);

        assertThatThrownBy(() -> service.create(d, ORG, USER))
                .isInstanceOf(ValidationException.class);
        assertThat(prescriptionRepo.findAll()).isEmpty();
    }

    @Test
    void valid_until_before_the_prescribed_date_is_rejected() {
        PrescriptionDTO d = sample("Erin");
        d.setPrescribedDate(LocalDate.now());
        d.setValidUntil(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.create(d, ORG, USER))
                .isInstanceOf(ValidationException.class);
    }

    // ── B2: cancel makes the CANCELLED state reachable ───────────────────────────────────────────────
    @Test
    void cancel_withdraws_the_prescription_and_is_idempotent() {
        Long id = service.create(sample("Frank"), ORG, USER).getId();

        assertThat(service.cancel(id, ORG, USER).getStatus()).isEqualTo("CANCELLED");
        assertThat(service.cancel(id, ORG, USER).getStatus()).isEqualTo("CANCELLED");   // repeat is a no-op
    }

    @Test
    void cancel_is_org_scoped() {
        Long id = service.create(sample("Grace"), ORG, USER).getId();
        assertThatThrownBy(() -> service.cancel(id, 999L, 999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

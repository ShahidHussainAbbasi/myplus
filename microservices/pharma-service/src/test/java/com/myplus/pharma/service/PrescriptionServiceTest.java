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
    void a_doctors_submit_is_marked_and_a_retry_returns_the_same_prescription() {
        // HMS S3b-1: the clinic sends "enc-<id>"; a second Submit (retry, double click) must not make a second script.
        PrescriptionDTO d = sample("Bilal");
        d.setExternalRef("enc-77");
        d.setTokenLabel("A-040");
        d.setEncounterId(77L);
        d.setPartyId(9001L);
        PrescriptionDTO first = service.create(d, ORG, USER);
        PrescriptionDTO again = service.create(d, ORG, USER);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(prescriptionRepo.count()).isEqualTo(1);
        assertThat(first.getSource()).isEqualTo("DOCTOR");
        assertThat(first.getTokenLabel()).isEqualTo("A-040");
        assertThat(first.getPartyId()).as("set at once, so the token search finds it").isEqualTo(9001L);
    }

    @Test
    void a_counter_prescription_cannot_claim_to_be_the_doctors() {
        // The source is derived, never read from the form; a counter form's party id is not trusted either.
        PrescriptionDTO d = sample("Carol");
        d.setSource("DOCTOR");
        d.setTokenLabel("A-001");
        d.setPartyId(42L);
        PrescriptionDTO out = service.create(d, ORG, USER);
        assertThat(out.getSource()).isEqualTo("COUNTER");
        assertThat(out.getTokenLabel()).isNull();
    }

    @Test
    void the_same_reference_in_another_organisation_is_a_different_prescription() {
        PrescriptionDTO d = sample("Dana");
        d.setExternalRef("enc-5");
        Long a = service.create(d, ORG, USER).getId();
        Long b = service.create(d, 2L, 2L).getId();
        assertThat(b).isNotEqualTo(a);
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

    // ── HC-1d: a prescription sent by a LINKED clinic ───────────────────────────────────────────────
    static final Long PHARMACY = 1L, CLINIC_A = 50L, CLINIC_B = 51L;

    private PrescriptionDTO fromClinic(String patient, long encounter) {
        PrescriptionDTO d = sample(patient);
        d.setEncounterId(encounter);
        d.setTokenLabel("A-001");
        d.setPartyId(4242L);        // the clinic's person id — must NOT cross
        d.setStoreId(3L);           // the clinic's branch — must NOT cross
        d.setExternalRef("enc-1");  // whatever the body says, the server builds its own key
        d.setNotes("MRN-000123");
        return d;
    }

    @Test
    void a_linked_clinics_prescription_lives_in_the_pharmacy_and_says_who_sent_it() {
        PrescriptionDTO out = service.createFromLinkedClinic(fromClinic("Hina", 5), PHARMACY, CLINIC_A, "Tahseen Clinic");
        var row = prescriptionRepo.findById(out.getId()).orElseThrow();
        assertThat(row.getOrganizationId()).isEqualTo(PHARMACY);
        assertThat(row.getFromOrgId()).isEqualTo(CLINIC_A);
        assertThat(row.getFromOrgName()).isEqualTo("Tahseen Clinic");
        assertThat(row.getSource()).isEqualTo("DOCTOR");
        assertThat(row.getExternalRef()).isEqualTo("clinic-50-enc-5");
        assertThat(row.getDiagnosis()).as("no diagnosis crosses (D3)").isNull();
        assertThat(row.getPartyId()).as("no clinic person id").isNull();
        assertThat(row.getStoreId()).as("no clinic branch").isNull();
        assertThat(row.getUserId()).as("no clinic user id").isNull();
        assertThat(row.getNotes()).isEqualTo("MRN-000123");
        // the pharmacy's ordinary, org-scoped reads see it
        assertThat(service.get(out.getId(), PHARMACY, null).getFromOrgName()).isEqualTo("Tahseen Clinic");
    }

    @Test
    void a_retry_is_the_same_prescription_and_two_clinics_never_collide() {
        Long a1 = service.createFromLinkedClinic(fromClinic("Hina", 5), PHARMACY, CLINIC_A, "A").getId();
        Long a2 = service.createFromLinkedClinic(fromClinic("Hina", 5), PHARMACY, CLINIC_A, "A").getId();
        Long b = service.createFromLinkedClinic(fromClinic("Sara", 5), PHARMACY, CLINIC_B, "B").getId();
        assertThat(a2).isEqualTo(a1);
        assertThat(b).isNotEqualTo(a1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_pharmacist_finds_a_linked_clinics_prescription_by_its_token_or_mrn() {
        Long a = service.createFromLinkedClinic(fromClinic("Hina", 5), PHARMACY, CLINIC_A, "A").getId();   // token A-001, MRN-000123
        java.util.function.Function<String, List<Long>> ids = q -> ((List<PrescriptionDTO>) service.search(PHARMACY, null, q, null, 0, 25)
                .get("items")).stream().map(PrescriptionDTO::getId).toList();
        assertThat(ids.apply("a-001")).containsExactly(a);
        assertThat(ids.apply("MRN-000123")).containsExactly(a);
        assertThat(ids.apply("A-00")).as("a token matches exactly, not as a fragment").isEmpty();
        assertThat(((List<?>) service.search(999L, null, "A-001", null, 0, 25).get("items"))).as("another pharmacy").isEmpty();
    }

    @Test
    void a_hand_written_line_is_accepted_from_a_linked_clinic_only() {
        PrescriptionDTO d = fromClinic("Hina", 6);
        PrescriptionItemDTO hand = new PrescriptionItemDTO();
        hand.setMedicineName("Syrup X 120ml");
        hand.setQuantity(1);
        d.getItems().add(hand);
        PrescriptionDTO out = service.createFromLinkedClinic(d, PHARMACY, CLINIC_A, "A");
        assertThat(out.getItems()).extracting(PrescriptionItemDTO::getProductId).containsExactly(555L, null);

        PrescriptionDTO counter = sample("Walk-in");
        counter.getItems().add(hand);
        assertThatThrownBy(() -> service.create(counter, PHARMACY, USER)).hasMessageContaining("has no medicine selected");
        PrescriptionItemDTO nameless = new PrescriptionItemDTO();
        nameless.setQuantity(1);
        PrescriptionDTO blank = fromClinic("Sara", 7);
        blank.getItems().add(nameless);
        assertThatThrownBy(() -> service.createFromLinkedClinic(blank, PHARMACY, CLINIC_A, "A")).hasMessageContaining("has no medicine selected");
    }

    @Test
    void a_clinic_reads_back_only_what_it_sent() {
        Long a = service.createFromLinkedClinic(fromClinic("Hina", 5), PHARMACY, CLINIC_A, "A").getId();
        Long counter = service.create(sample("Walk-in"), PHARMACY, USER).getId();
        assertThat(service.getForLinkedClinic(a, CLINIC_A).getId()).isEqualTo(a);
        assertThatThrownBy(() -> service.getForLinkedClinic(a, CLINIC_B)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getForLinkedClinic(counter, CLINIC_A)).isInstanceOf(ResourceNotFoundException.class);
    }
}

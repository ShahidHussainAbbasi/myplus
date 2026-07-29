package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.dto.ControlledDispenseDTO;
import com.myplus.pharma.dto.DispenseRequest;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.dto.PrescriptionItemDTO;
import com.myplus.pharma.repository.DispensingRepository;
import com.myplus.pharma.repository.PrescriptionItemRepository;
import com.myplus.pharma.repository.PrescriptionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The pharmacy business cases, as executable scenarios — the real-life examples a pharmacist
 * describes, rather than the unit mechanics (which DispenseServiceTest / PrescriptionServiceTest
 * already cover).
 *
 * Scope note: Example 3 ("the safety lock") is deliberately NOT here. The prescription-only refusal
 * lives in business-service's SagaSellService, because the sell path must not depend on
 * pharma-service being up. It cannot be exercised from this module — see the class comment at the
 * bottom for where it is tested instead.
 *
 * Real MySQL via Testcontainers; skips without Docker. Runs on {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PrescriptionUseCaseTest {

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

    // Catalog product ids standing in for real medicines.
    private static final Long AZITHROMYCIN = 501L;
    private static final Long AUGMENTIN    = 502L;
    private static final Long BRUFEN       = 503L;
    private static final Long TRAMADOL     = 504L;   // controlled

    /** Clinical flags live on the catalog product (B1); mocked so these tests are about the pharmacy flow. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.myplus.commerce.contracts.client.CatalogClient catalogClient;

    @Autowired private PrescriptionService prescriptionService;
    @Autowired private DispenseService dispenseService;
    @Autowired private SafetyService safetyService;
    @Autowired private PrescriptionRepository prescriptionRepo;
    @Autowired private PrescriptionItemRepository itemRepo;
    @Autowired private DispensingRepository dispensingRepo;

    @BeforeEach
    void clean() {
        dispensingRepo.deleteAll();
        itemRepo.deleteAll();
        prescriptionRepo.deleteAll();
        // Only Tramadol is a controlled substance in this fixture.
        when(catalogClient.getProducts(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> {
                ProductRef r = new ProductRef();
                r.setId((Long) id);
                r.setControlledSubstance(TRAMADOL.equals(id));
                r.setRxRequired(AUGMENTIN.equals(id) || AZITHROMYCIN.equals(id));
                return r;
            }).toList();
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private PrescriptionItemDTO line(Long productId, String name, int qty) {
        PrescriptionItemDTO it = new PrescriptionItemDTO();
        it.setProductId(productId);
        it.setMedicineName(name);
        it.setQuantity(qty);
        return it;
    }

    private PrescriptionDTO script(String patient, String doctor, LocalDate validUntil,
                                   PrescriptionItemDTO... lines) {
        PrescriptionDTO d = new PrescriptionDTO();
        d.setPatientName(patient);
        d.setDoctorName(doctor);
        d.setValidUntil(validUntil);
        for (PrescriptionItemDTO l : lines) d.getItems().add(l);
        return d;
    }

    /** One "trip to the counter": the sale already happened, this records what it satisfied. */
    private PrescriptionDTO dispense(Long rxId, String invoiceNo, Long productId, int qty) {
        DispenseRequest req = new DispenseRequest();
        req.setInvoiceNo(invoiceNo);
        DispenseRequest.Line l = new DispenseRequest.Line();
        l.setProductId(productId);
        l.setQuantity(qty);
        req.setItems(List.of(l));
        return dispenseService.dispense(rxId, req, ORG, USER);
    }

    // ── Example 1 — full collection ────────────────────────────────────────────

    @Nested
    @DisplayName("Example 1 — Ayesha collects the whole script in one go")
    class FullCollection {

        @Test
        void recording_the_script_alone_dispenses_nothing() {
            Long rx = prescriptionService.create(
                    script("Ayesha", "Dr. Khan", LocalDate.now().plusDays(30),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();

            PrescriptionDTO saved = prescriptionService.get(rx, ORG, USER);

            // The whole point of the split: a clinical entry moves no stock and takes no money.
            // All this module can show is that nothing has been handed over yet.
            assertThat(saved.getStatus()).isEqualTo("PENDING");
            assertThat(saved.getItems().get(0).getDispensedQuantity()).isZero();
            assertThat(dispensingRepo.count()).as("no dispense record until a sale satisfies it").isZero();
        }

        @Test
        void dispensing_the_full_quantity_completes_the_script() {
            Long rx = prescriptionService.create(
                    script("Ayesha", "Dr. Khan", LocalDate.now().plusDays(30),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();

            PrescriptionDTO after = dispense(rx, "INV-1001", AZITHROMYCIN, 6);

            assertThat(after.getStatus()).isEqualTo("FULLY_DISPENSED");
            assertThat(after.getItems().get(0).getDispensedQuantity()).isEqualTo(6);
            assertThat(after.getWarnings()).isEmpty();
        }

        @Test
        void a_completed_script_cannot_be_dispensed_again() {
            Long rx = prescriptionService.create(
                    script("Ayesha", "Dr. Khan", LocalDate.now().plusDays(30),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();
            dispense(rx, "INV-1001", AZITHROMYCIN, 6);

            // Not an exception — the sale is allowed to happen, but nothing more can be charged to
            // this script, and the pharmacist is told rather than left to assume it counted.
            PrescriptionDTO again = dispense(rx, "INV-1002", AZITHROMYCIN, 6);

            assertThat(again.getItems().get(0).getDispensedQuantity())
                    .as("still 6 — a second sale cannot inflate what the script accounted for")
                    .isEqualTo(6);
            assertThat(again.getWarnings()).isNotEmpty();
        }
    }

    // ── Example 2 — partial collection ─────────────────────────────────────────

    @Nested
    @DisplayName("Example 2 — patient takes 2 of 3 medicines now, the third next week")
    class PartialCollection {

        private Long threeMedicineScript() {
            return prescriptionService.create(
                    script("Bilal", "Dr. Ahmed", LocalDate.now().plusDays(60),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 6),
                            line(BRUFEN, "Brufen 400mg", 10),
                            line(AUGMENTIN, "Augmentin 625mg", 14)), ORG, USER).getId();
        }

        @Test
        void taking_some_medicines_leaves_the_script_partially_dispensed() {
            Long rx = threeMedicineScript();

            DispenseRequest req = new DispenseRequest();
            req.setInvoiceNo("INV-2001");
            DispenseRequest.Line a = new DispenseRequest.Line();
            a.setProductId(AZITHROMYCIN); a.setQuantity(6);
            DispenseRequest.Line b = new DispenseRequest.Line();
            b.setProductId(BRUFEN); b.setQuantity(10);
            req.setItems(List.of(a, b));

            PrescriptionDTO after = dispenseService.dispense(rx, req, ORG, USER);

            assertThat(after.getStatus()).isEqualTo("PARTIALLY_DISPENSED");
        }

        @Test
        void the_system_remembers_what_is_still_outstanding_a_week_later() {
            Long rx = threeMedicineScript();

            DispenseRequest first = new DispenseRequest();
            first.setInvoiceNo("INV-2001");
            DispenseRequest.Line a = new DispenseRequest.Line();
            a.setProductId(AZITHROMYCIN); a.setQuantity(6);
            first.setItems(List.of(a));
            dispenseService.dispense(rx, first, ORG, USER);

            // Next week, same script — the pharmacist doesn't have to remember anything.
            PrescriptionDTO before = prescriptionService.get(rx, ORG, USER);
            assertThat(outstanding(before, AUGMENTIN)).isEqualTo(14);

            PrescriptionDTO after = dispense(rx, "INV-2002", AUGMENTIN, 14);
            assertThat(outstanding(after, AUGMENTIN)).isZero();
            assertThat(after.getStatus())
                    .as("Brufen is still outstanding, so the script is not complete")
                    .isEqualTo("PARTIALLY_DISPENSED");
        }

        @Test
        void collecting_the_last_medicine_completes_the_script() {
            Long rx = threeMedicineScript();
            dispense(rx, "INV-2001", AZITHROMYCIN, 6);
            dispense(rx, "INV-2002", BRUFEN, 10);

            PrescriptionDTO after = dispense(rx, "INV-2003", AUGMENTIN, 14);

            assertThat(after.getStatus()).isEqualTo("FULLY_DISPENSED");
        }

        @Test
        void a_part_quantity_of_one_medicine_is_tracked_too() {
            // "Give me 4 tablets now, I'll take the other 2 later."
            Long rx = prescriptionService.create(
                    script("Bilal", "Dr. Ahmed", LocalDate.now().plusDays(60),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();

            PrescriptionDTO after = dispense(rx, "INV-2100", AZITHROMYCIN, 4);
            assertThat(after.getStatus()).isEqualTo("PARTIALLY_DISPENSED");
            assertThat(outstanding(after, AZITHROMYCIN)).isEqualTo(2);

            PrescriptionDTO done = dispense(rx, "INV-2101", AZITHROMYCIN, 2);
            assertThat(done.getStatus()).isEqualTo("FULLY_DISPENSED");
        }

        private int outstanding(PrescriptionDTO rx, Long productId) {
            return rx.getItems().stream()
                    .filter(i -> productId.equals(i.getProductId()))
                    .findFirst()
                    .map(i -> i.getQuantity() - i.getDispensedQuantity())
                    .orElseThrow();
        }
    }

    // ── Example 4 — cancellation ───────────────────────────────────────────────

    @Nested
    @DisplayName("Example 4 — script withdrawn by the doctor, or entered by mistake")
    class Cancellation {

        @Test
        void cancelling_stops_any_future_dispensing() {
            Long rx = prescriptionService.create(
                    script("Ayesha", "Dr. Khan", LocalDate.now().plusDays(30),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();

            prescriptionService.cancel(rx, ORG, USER);

            assertThatThrownBy(() -> dispense(rx, "INV-3001", AZITHROMYCIN, 6))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("cancelled");
        }

        @Test
        void what_was_already_handed_over_yesterday_stays_on_the_record() {
            // A partial dispense, so there is BOTH history and outstanding quantity when it is cancelled.
            Long rx = prescriptionService.create(
                    script("Ayesha", "Dr. Khan", LocalDate.now().plusDays(30),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 10)), ORG, USER).getId();
            dispense(rx, "INV-3101", AZITHROMYCIN, 4);

            prescriptionService.cancel(rx, ORG, USER);

            PrescriptionDTO after = prescriptionService.get(rx, ORG, USER);
            assertThat(after.getStatus()).isEqualTo("CANCELLED");
            assertThat(after.getItems().get(0).getDispensedQuantity())
                    .as("cancelling is about the FUTURE — it is not a rewrite of history")
                    .isEqualTo(4);
            assertThat(dispensingRepo.count()).as("the audit trail survives the cancellation").isEqualTo(1);
        }

        @Test
        void a_fully_dispensed_script_cannot_be_cancelled() {
            Long rx = prescriptionService.create(
                    script("Ayesha", "Dr. Khan", LocalDate.now().plusDays(30),
                            line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();
            dispense(rx, "INV-3200", AZITHROMYCIN, 6);

            // You cannot un-hand-over medicine that has already gone.
            assertThatThrownBy(() -> prescriptionService.cancel(rx, ORG, USER))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // ── Example 5 — the controlled-substance register ──────────────────────────

    @Nested
    @DisplayName("Example 5 — the regulator asks what controlled medicine was dispensed")
    class ControlledRegister {

        @Test
        void a_controlled_dispense_appears_on_the_register() {
            Long rx = prescriptionService.create(
                    script("Kamran", "Dr. Saleem", LocalDate.now().plusDays(30),
                            line(TRAMADOL, "Tramadol 50mg", 10)), ORG, USER).getId();

            dispense(rx, "INV-4001", TRAMADOL, 10);

            List<ControlledDispenseDTO> register = safetyService.controlledRegister(ORG, USER);
            assertThat(register).hasSize(1);
            assertThat(register.get(0).getMedicineName()).isEqualTo("Tramadol 50mg");
            assertThat(register.get(0).getQuantity()).isEqualTo(10);
            assertThat(register.get(0).getPatientName()).isEqualTo("Kamran");
            assertThat(register.get(0).getInvoiceNo())
                    .as("the register ties back to the sale that handed it over")
                    .isEqualTo("INV-4001");
        }

        @Test
        void an_ordinary_medicine_does_not_reach_the_register() {
            Long rx = prescriptionService.create(
                    script("Kamran", "Dr. Saleem", LocalDate.now().plusDays(30),
                            line(BRUFEN, "Brufen 400mg", 10)), ORG, USER).getId();

            dispense(rx, "INV-4002", BRUFEN, 10);

            assertThat(safetyService.controlledRegister(ORG, USER))
                    .as("only controlled substances are registrable")
                    .isEmpty();
        }

        /**
         * DOCUMENTS A GAP, and fails the day it is closed so the docs get updated with it.
         *
         * The business description claims the register answers a regulator "with doctor and patient
         * CNIC". It does not: the row carries no prescriber, no prescription id and no CNIC (there is
         * no CNIC field on a prescription at all). {@code dispensedBy} is the STAFF member who
         * dispensed — not the doctor who prescribed. Tracked as pharmacy review item E.
         */
        @Test
        void the_register_does_NOT_yet_carry_the_prescriber_or_a_patient_id() {
            Long rx = prescriptionService.create(
                    script("Kamran", "Dr. Saleem", LocalDate.now().plusDays(30),
                            line(TRAMADOL, "Tramadol 50mg", 10)), ORG, USER).getId();
            dispense(rx, "INV-4003", TRAMADOL, 10);

            ControlledDispenseDTO row = safetyService.controlledRegister(ORG, USER).get(0);

            assertThat(row.getDispensedBy())
                    .as("this is the staff user who dispensed, NOT the prescriber")
                    .isEqualTo(USER);

            List<String> fields = java.util.Arrays.stream(ControlledDispenseDTO.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName).toList();
            assertThat(fields)
                    .as("if any of these appear, item E has been done — update the use-case doc")
                    .doesNotContain("doctorName", "doctorLicense", "prescriptionId", "patientCnic", "batchNo");
        }
    }

    // ── Expiry (mentioned in the description as "until when is it valid") ──────

    @Test
    @DisplayName("A script past its validity cannot be dispensed")
    void an_expired_script_is_refused() {
        Long rx = prescriptionService.create(
                script("Ayesha", "Dr. Khan", LocalDate.now().minusDays(1),
                        line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();

        assertThatThrownBy(() -> dispense(rx, "INV-5001", AZITHROMYCIN, 6))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("One patient's script is invisible to another tenant")
    void a_script_is_scoped_to_its_organization() {
        Long rx = prescriptionService.create(
                script("Ayesha", "Dr. Khan", LocalDate.now().plusDays(30),
                        line(AZITHROMYCIN, "Azithromycin 500mg", 6)), ORG, USER).getId();

        Long otherOrg = 99L;
        assertThatThrownBy(() -> dispenseService.dispense(rx, new DispenseRequest(), otherOrg, 99L))
                .as("another pharmacy must not be able to dispense against this script")
                .isInstanceOf(RuntimeException.class);
    }
}

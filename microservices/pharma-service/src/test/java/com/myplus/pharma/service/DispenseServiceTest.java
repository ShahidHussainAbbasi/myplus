package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.dto.DispenseRequest;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.dto.PrescriptionItemDTO;
import com.myplus.pharma.repository.DispensingRepository;
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

import java.time.LocalDate;

/**
 * P6 (slice 43) — dispense bumps dispensedQuantity (capped), writes a Dispensing record linked to the sale invoice,
 * and recomputes status (PENDING → PARTIALLY → FULLY). Real MySQL; skips without Docker.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class DispenseServiceTest {

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

    private static final Long ORG = 1L, USER = 1L, ITEM = 555L;

    @Autowired private PrescriptionService prescriptionService;
    @Autowired private DispenseService dispenseService;
    @Autowired private PrescriptionRepository prescriptionRepo;
    @Autowired private PrescriptionItemRepository itemRepo;
    @Autowired private DispensingRepository dispensingRepo;

    @BeforeEach
    void clean() { dispensingRepo.deleteAll(); itemRepo.deleteAll(); prescriptionRepo.deleteAll(); }

    private Long newRx() {
        PrescriptionDTO d = new PrescriptionDTO();
        d.setPatientName("Alice");
        PrescriptionItemDTO it = new PrescriptionItemDTO();
        it.setProductId(ITEM); it.setMedicineName("Paracetamol"); it.setQuantity(20);
        d.getItems().add(it);
        return prescriptionService.create(d, ORG, USER).getId();
    }

    /**
     * NOTE: each call takes its own invoice. Two genuine part-fills are two separate SALES and so carry two
     * different invoice numbers; re-posting the SAME invoice is the duplicate case, which is now ignored on
     * purpose (see repeat_post_of_the_same_invoice_is_ignored).
     */
    private DispenseRequest req(String invoiceNo, int qty) {
        DispenseRequest r = new DispenseRequest();
        r.setInvoiceNo(invoiceNo);
        DispenseRequest.Line l = new DispenseRequest.Line();
        l.setProductId(ITEM); l.setQuantity(qty);
        r.getItems().add(l);
        return r;
    }

    @Test
    void partial_then_full_dispense_updates_quantities_and_status() {
        Long id = newRx();

        PrescriptionDTO afterHalf = dispenseService.dispense(id, req("INV-000001", 10), ORG, USER);
        assertThat(afterHalf.getStatus()).isEqualTo("PARTIALLY_DISPENSED");
        assertThat(afterHalf.getItems().get(0).getDispensedQuantity()).isEqualTo(10);

        PrescriptionDTO afterFull = dispenseService.dispense(id, req("INV-000002", 10), ORG, USER);
        assertThat(afterFull.getStatus()).isEqualTo("FULLY_DISPENSED");
        assertThat(afterFull.getItems().get(0).getDispensedQuantity()).isEqualTo(20);
        assertThat(dispensingRepo.findAll()).hasSize(2);   // one Dispensing record per sale
    }

    @Test
    void dispense_never_exceeds_the_prescribed_quantity() {
        Long id = newRx();
        dispenseService.dispense(id, req("INV-000001", 15), ORG, USER);
        PrescriptionDTO over = dispenseService.dispense(id, req("INV-000002", 99), ORG, USER);   // only 5 room left
        assertThat(over.getItems().get(0).getDispensedQuantity()).isEqualTo(20);   // capped at prescribed 20
        assertThat(over.getStatus()).isEqualTo("FULLY_DISPENSED");
        // B4: the cap is no longer silent — the sale released 99, the record accounts for 5.
        assertThat(over.getWarnings()).isNotEmpty();
    }

    // ── B3: idempotency ──────────────────────────────────────────────────────────────────────────────
    @Test
    void repeat_post_of_the_same_invoice_is_ignored() {
        Long id = newRx();
        dispenseService.dispense(id, req("INV-DUP", 10), ORG, USER);

        // The sale flow retries under its idempotency key, gets the SAME invoice back, and re-fires the dispense.
        PrescriptionDTO repeat = dispenseService.dispense(id, req("INV-DUP", 10), ORG, USER);

        assertThat(repeat.getItems().get(0).getDispensedQuantity()).isEqualTo(10);   // NOT 20
        assertThat(repeat.getStatus()).isEqualTo("PARTIALLY_DISPENSED");
        assertThat(dispensingRepo.findAll()).hasSize(1);                             // register not double-listed
        assertThat(repeat.getWarnings()).isNotEmpty();
    }

    // ── B2: only a live prescription can be dispensed ────────────────────────────────────────────────
    @Test
    void a_cancelled_prescription_cannot_be_dispensed() {
        Long id = newRx();
        prescriptionService.cancel(id, ORG, USER);

        assertThatThrownBy(() -> dispenseService.dispense(id, req("INV-000001", 5), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cancelled");
        assertThat(dispensingRepo.findAll()).isEmpty();
    }

    @Test
    void an_expired_prescription_cannot_be_dispensed_and_reads_as_expired() {
        PrescriptionDTO d = new PrescriptionDTO();
        d.setPatientName("Bob");
        d.setPrescribedDate(LocalDate.now().minusDays(30));
        d.setValidUntil(LocalDate.now().minusDays(1));       // lapsed yesterday
        PrescriptionItemDTO it = new PrescriptionItemDTO();
        it.setProductId(ITEM); it.setMedicineName("Paracetamol"); it.setQuantity(20);
        d.getItems().add(it);
        Long id = prescriptionService.create(d, ORG, USER).getId();

        assertThatThrownBy(() -> dispenseService.dispense(id, req("INV-000001", 5), ORG, USER))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("expired");
        // Expiry is derived, so it shows without any nightly job having run.
        assertThat(prescriptionService.get(id, ORG, USER).getStatus()).isEqualTo("EXPIRED");
    }

    // ── B4: what the prescription can't account for is reported ──────────────────────────────────────
    @Test
    void an_item_sold_that_is_not_on_the_prescription_is_reported_not_silently_dropped() {
        Long id = newRx();
        DispenseRequest r = req("INV-000001", 5);
        DispenseRequest.Line stray = new DispenseRequest.Line();
        stray.setProductId(9999L); stray.setQuantity(2);     // sold alongside, not prescribed
        r.getItems().add(stray);

        PrescriptionDTO out = dispenseService.dispense(id, r, ORG, USER);

        assertThat(out.getItems().get(0).getDispensedQuantity()).isEqualTo(5);
        assertThat(out.getWarnings()).anyMatch(w -> w.contains("9999"));
    }
}

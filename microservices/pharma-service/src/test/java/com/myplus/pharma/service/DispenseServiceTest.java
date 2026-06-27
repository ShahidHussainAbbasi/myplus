package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;

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
        it.setItemId(ITEM); it.setMedicineName("Paracetamol"); it.setQuantity(20);
        d.getItems().add(it);
        return prescriptionService.create(d, ORG, USER).getId();
    }

    private DispenseRequest req(int qty) {
        DispenseRequest r = new DispenseRequest();
        r.setInvoiceNo("INV-000001");
        DispenseRequest.Line l = new DispenseRequest.Line();
        l.setItemId(ITEM); l.setQuantity(qty);
        r.getItems().add(l);
        return r;
    }

    @Test
    void partial_then_full_dispense_updates_quantities_and_status() {
        Long id = newRx();

        PrescriptionDTO afterHalf = dispenseService.dispense(id, req(10), ORG, USER);
        assertThat(afterHalf.getStatus()).isEqualTo("PARTIALLY_DISPENSED");
        assertThat(afterHalf.getItems().get(0).getDispensedQuantity()).isEqualTo(10);

        PrescriptionDTO afterFull = dispenseService.dispense(id, req(10), ORG, USER);
        assertThat(afterFull.getStatus()).isEqualTo("FULLY_DISPENSED");
        assertThat(afterFull.getItems().get(0).getDispensedQuantity()).isEqualTo(20);
        assertThat(dispensingRepo.findAll()).hasSize(2);   // two Dispensing records, both linked to the invoice
    }

    @Test
    void dispense_never_exceeds_the_prescribed_quantity() {
        Long id = newRx();
        dispenseService.dispense(id, req(15), ORG, USER);
        PrescriptionDTO over = dispenseService.dispense(id, req(99), ORG, USER);   // only 5 room left
        assertThat(over.getItems().get(0).getDispensedQuantity()).isEqualTo(20);   // capped at prescribed 20
        assertThat(over.getStatus()).isEqualTo("FULLY_DISPENSED");
    }
}

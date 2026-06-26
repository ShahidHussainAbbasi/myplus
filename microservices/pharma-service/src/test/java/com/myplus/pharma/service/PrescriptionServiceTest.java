package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
        it.setItemId(555L);
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
        assertThat(got.getItems().get(0).getItemId()).isEqualTo(555L);
        assertThat(got.getItems().get(0).getFrequency()).isEqualTo("TDS");
    }

    @Test
    void list_is_org_scoped() {
        service.create(sample("Bob"), ORG, USER);
        assertThat(service.list(ORG, USER)).hasSize(1);
        assertThat(service.list(999L, 999L)).isEmpty();   // another tenant sees nothing
    }
}

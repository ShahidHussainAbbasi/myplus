package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.support.TestTenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PH-FORMULA — a medicine's formula: one formula is one value, hiding the field never deletes it, and a tenant only
 * ever sees its own. Against real MySQL on purpose: whether "Paracetamol 500mg" and "paracetamol 500mg" are ONE entry
 * in the list is the column collation's answer, not Java's.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductFormulaTest {

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

    @Autowired private ProductService service;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        TestTenant.authenticate();
    }

    @AfterEach
    void tearDown() { TestTenant.clear(); }

    private ProductDTO brand(String name, String formula) {
        return service.create(ProductDTO.builder().name(name).sellingPrice(new BigDecimal("50.00"))
                .formula(formula).build());
    }

    @Test
    @DisplayName("normalised at write: trimmed, inner spaces collapsed, blank → NULL")
    void normalisesAtWrite() {
        assertThat(ProductService.normalizeFormula("  Paracetamol   500mg  ")).isEqualTo("Paracetamol 500mg");
        assertThat(ProductService.normalizeFormula("   ")).isNull();
        assertThat(ProductService.normalizeFormula(null)).isNull();
        assertThat(brand("Panadol", "  Paracetamol   500mg ").getFormula()).isEqualTo("Paracetamol 500mg");
    }

    @Test
    @DisplayName("⭐ two brands, one formula typed differently → ONE entry in the autocomplete list")
    void oneFormulaOneEntry() {
        brand("Panadol", "Paracetamol 500mg");
        brand("Calpol", "paracetamol   500mg");   // different case AND spacing
        brand("Brufen", "Ibuprofen 400mg");
        assertThat(service.formulas()).hasSize(2);
        assertThat(service.formulas()).anyMatch(f -> f.equalsIgnoreCase("Paracetamol 500mg"));
    }

    @Test
    @DisplayName("⭐ hidden ≠ deleted: an update that OMITS formula keeps it; a CLEARED box removes it")
    void absentKeepsBlankClears() {
        ProductDTO p = brand("Panadol", "Paracetamol 500mg");

        // The field is hidden on this tenant's form, so the update carries no formula at all.
        ProductDTO kept = service.update(p.getId(), ProductDTO.builder().name("Panadol Extra")
                .sellingPrice(new BigDecimal("55.00")).build());
        assertThat(kept.getFormula()).as("omitted → the stored formula survives").isEqualTo("Paracetamol 500mg");

        // The field is visible and the user emptied it.
        ProductDTO cleared = service.update(p.getId(), ProductDTO.builder().name("Panadol Extra")
                .sellingPrice(new BigDecimal("55.00")).formula("").build());
        assertThat(cleared.getFormula()).as("an emptied box is an answer: no formula").isNull();
    }

    @Test
    @DisplayName("⭐ anti-IDOR: another tenant's formulas never appear in this tenant's list")
    void listIsTenantScoped() {
        brand("Panadol", "Paracetamol 500mg");
        TestTenant.authenticate(99L, 99L);
        brand("Other", "Secret 1mg");
        assertThat(service.formulas()).containsExactly("Secret 1mg");
        TestTenant.authenticate();
        assertThat(service.formulas()).containsExactly("Paracetamol 500mg");
    }

    @Test
    @DisplayName("the till's picker carries the formula, so it can be searched by it")
    void pickerCarriesFormula() {
        brand("Panadol", "Paracetamol 500mg");
        brand("Plain", null);
        List<com.myplus.catalog.dto.ProductPickerDTO> rows = productRepository
                .findPickerScoped(TestTenant.ORG, TestTenant.USER, PageRequest.of(0, 50)).getContent();
        assertThat(rows).filteredOn(r -> "Panadol".equals(r.getName()))
                .singleElement().extracting(com.myplus.catalog.dto.ProductPickerDTO::getFormula)
                .isEqualTo("Paracetamol 500mg");
        assertThat(rows).filteredOn(r -> "Plain".equals(r.getName()))
                .singleElement().extracting(com.myplus.catalog.dto.ProductPickerDTO::getFormula).isNull();
    }
}

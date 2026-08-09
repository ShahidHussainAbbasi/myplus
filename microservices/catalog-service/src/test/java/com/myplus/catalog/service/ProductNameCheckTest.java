package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.myplus.catalog.dto.NameCheckDTO;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.repository.TaxCodeRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Duplicate-NAME check behind the Product form's focus-out warning.
 *
 * <p>Pure logic: mocked repository, no Spring, no database, no Docker — runs on every {@code mvn test}, which
 * matters because this module's other product tests are Testcontainers suites that skip on the dev machine.
 *
 * <p>The cases worth pinning are the ones that fail silently in the UI rather than throwing: a name that
 * differs only by case or padding treated as new, a product warning about ITSELF the moment it is edited, and
 * a blank name burning a query on every focus-out of an untouched form.
 */
@ExtendWith(MockitoExtension.class)
class ProductNameCheckTest {

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private TaxCodeRepository taxCodeRepository;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository, taxCodeRepository);
        // No authentication in the context: CurrentUser then yields null org/user, which is fine here —
        // these tests assert what the SERVICE does with the repository's answer, and the scoping itself is
        // proven against a real dialect in ProductRepoScopingTest.
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Product product(Long id, String name, String sku, Boolean active) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setSku(sku);
        p.setIsActive(active);
        return p;
    }

    @Test
    @DisplayName("a namesake is reported, and named well enough to link to")
    void reportsTheNamesake() {
        when(productRepository.findByNameScoped(any(), any(), any()))
                .thenReturn(List.of(product(12L, "Panadol 500mg", "PAN500", true)));

        NameCheckDTO r = service.checkName("Panadol 500mg", null);

        assertThat(r.exists()).isTrue();
        assertThat(r.id()).isEqualTo(12L);
        // The name AS STORED, not as typed — the form shows the operator the existing spelling.
        assertThat(r.name()).isEqualTo("Panadol 500mg");
        assertThat(r.sku()).isEqualTo("PAN500");
        assertThat(r.active()).isTrue();
    }

    @Test
    @DisplayName("no namesake reports a clean all-clear, not a half-filled answer")
    void reportsNoNamesake() {
        when(productRepository.findByNameScoped(any(), any(), any())).thenReturn(List.of());

        NameCheckDTO r = service.checkName("Something New", null);

        assertThat(r.exists()).isFalse();
        assertThat(r.id()).isNull();
        assertThat(r.name()).isNull();
    }

    /**
     * The query matches on {@code LOWER(TRIM(name))}, so the value handed to it must already be lower-cased —
     * otherwise "PANADOL" compares lower-cased-column against upper-cased-parameter and never matches, and the
     * form cheerfully reports a duplicate name as free.
     */
    @Test
    @DisplayName("the name is trimmed and lower-cased before it reaches the query")
    void normalisesTheNameForTheQuery() {
        when(productRepository.findByNameScoped(any(), any(), any())).thenReturn(List.of());

        service.checkName("  PaNaDoL 500mg  ", null);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(productRepository).findByNameScoped(name.capture(), any(), any());
        assertThat(name.getValue()).isEqualTo("panadol 500mg");
    }

    @Test
    @DisplayName("editing a product does not flag it against its own name")
    void excludesTheProductBeingEdited() {
        when(productRepository.findByNameScoped(any(), any(), any()))
                .thenReturn(List.of(product(12L, "Panadol 500mg", "PAN500", true)));

        assertThat(service.checkName("Panadol 500mg", 12L).exists())
                .as("the only match is the product being edited")
                .isFalse();
        assertThat(service.checkName("Panadol 500mg", 99L).exists())
                .as("a different product's name is still a duplicate")
                .isTrue();
    }

    @Test
    @DisplayName("with several namesakes, the excluded one is skipped rather than ending the search")
    void skipsPastTheExcludedProduct() {
        when(productRepository.findByNameScoped(any(), any(), any())).thenReturn(List.of(
                product(12L, "Panadol 500mg", "PAN500", true),
                product(13L, "Panadol 500mg", "PAN500B", true)));

        NameCheckDTO r = service.checkName("Panadol 500mg", 12L);

        assertThat(r.exists()).isTrue();
        assertThat(r.id()).as("the OTHER namesake is reported, not a false all-clear").isEqualTo(13L);
    }

    /**
     * A deactivated product still owns its name downstream (past sales, inventory), so it must be reported —
     * but the form has to know it is inactive, or "edit this one instead" sends the operator to a product that
     * has dropped off their list.
     */
    @Test
    @DisplayName("a deactivated namesake is reported as inactive, not hidden")
    void reportsADeactivatedNamesakeAsInactive() {
        when(productRepository.findByNameScoped(any(), any(), any()))
                .thenReturn(List.of(product(12L, "Panadol 500mg", "PAN500", false)));

        NameCheckDTO r = service.checkName("Panadol 500mg", null);

        assertThat(r.exists()).isTrue();
        assertThat(r.active()).isFalse();
    }

    /** A NULL isActive predates the column and means active everywhere else in the stack; agree with that. */
    @Test
    @DisplayName("a null isActive counts as active")
    void nullIsActiveCountsAsActive() {
        when(productRepository.findByNameScoped(any(), any(), any()))
                .thenReturn(List.of(product(12L, "Legacy Row", null, null)));

        assertThat(service.checkName("Legacy Row", null).active()).isTrue();
    }

    /**
     * The check fires on every focus-out of the Name field, including the untouched ones. A blank name must
     * cost nothing — a query per tab-through is a query nobody asked for.
     */
    @Test
    @DisplayName("a blank or null name answers without touching the database")
    void blankNameCostsNoQuery() {
        assertThat(service.checkName(null, null).exists()).isFalse();
        assertThat(service.checkName("", null).exists()).isFalse();
        assertThat(service.checkName("   ", null).exists()).isFalse();

        verify(productRepository, never()).findByNameScoped(any(), any(), any());
    }
}

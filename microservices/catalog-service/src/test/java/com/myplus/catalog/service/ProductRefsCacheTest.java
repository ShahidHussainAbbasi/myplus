package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.repository.TaxCodeRepository;
import com.myplus.catalog.support.TestTenant;
import com.myplus.commerce.contracts.dto.ProductRef;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * CACHE-3 — the product-ref cache behind the read screens, exercised through a REAL {@link CatalogRefsCache}.
 *
 * <p>{@code CatalogRefsWritersTest} mocks that collaborator, which is right for asserting WHICH call a writer makes
 * and useless for asserting what the cache DOES. Here the cache is real and the repository is the mock, so every case
 * below is about the query that did or did not reach the database.
 *
 * <p>The sale path is deliberately absent: it passes {@code fresh} and never touches this cache (case 3), which is the
 * user's rule that money is never decided from a remembered row. Design: {@code slices/cache-1-tenant-cache-aside.md}
 * §9.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // orgCodeRates() runs on load paths only; stubs are shared
class ProductRefsCacheTest {

    private static final Long ORG = 7L;
    private static final Long USER = 3L;
    private static final Long OTHER_ORG = 8L;

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private TaxCodeRepository taxCodeRepository;
    @Mock private ProductPickerCache pickerCache;
    @Mock private ApplicationEventPublisher events;

    private CatalogRefsCache refsCache;   // REAL — this class is about what it does
    private ProductService service;

    @BeforeEach
    void setUp() {
        TestTenant.authenticate(ORG, USER);
        ObjectProvider<MeterRegistry> noMetrics = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
        refsCache = new CatalogRefsCache(300, noMetrics);
        service = new ProductService(productRepository, categoryRepository, taxCodeRepository, pickerCache, events,
                refsCache);
        when(taxCodeRepository.findByOrganizationId(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    private static Product product(Long id, String name) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setOrganizationId(ORG);
        p.setUserId(USER);
        p.setSellingPrice(new BigDecimal("12.50"));
        return p;
    }

    private void databaseHolds(List<Long> ids, Product... found) {
        when(productRepository.findAllByIdScoped(eq(ids), any(), any())).thenReturn(List.of(found));
    }

    // ── cache-aside ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a repeat read of the same ids never reaches the database again")
    void repeat_read_is_served_from_the_cache() {
        databaseHolds(List.of(1L), product(1L, "Panadol"));

        assertThat(service.getRefs(List.of(1L))).extracting(ProductRef::getName).containsExactly("Panadol");
        assertThat(service.getRefs(List.of(1L))).extracting(ProductRef::getName).containsExactly("Panadol");

        verify(productRepository, times(1)).findAllByIdScoped(any(), any(), any());
    }

    @Test
    @DisplayName("⭐ a read that overlaps a cached one queries ONLY what was missing")
    void only_the_misses_are_queried() {
        databaseHolds(List.of(1L, 2L), product(1L, "Panadol"), product(2L, "Brufen"));
        service.getRefs(List.of(1L, 2L));

        databaseHolds(List.of(3L), product(3L, "Disprin"));
        List<ProductRef> second = service.getRefs(List.of(1L, 2L, 3L));

        // All three come back — two from the cache, one from a query for exactly that id.
        assertThat(second).extracting(ProductRef::getId).containsExactlyInAnyOrder(1L, 2L, 3L);
        verify(productRepository).findAllByIdScoped(eq(List.of(3L)), any(), any());
        verify(productRepository, never()).findAllByIdScoped(eq(List.of(1L, 2L, 3L)), any(), any());
    }

    @Test
    @DisplayName("⭐ fresh=true always reads the database — the sale path never sees a remembered row")
    void fresh_bypasses_the_cache() {
        databaseHolds(List.of(1L), product(1L, "Panadol"));

        service.getRefs(List.of(1L), true);
        service.getRefs(List.of(1L), true);

        verify(productRepository, times(2)).findAllByIdScoped(any(), any(), any());
    }

    @Test
    @DisplayName("a cached read does not poison the fresh one, nor the other way about")
    void the_two_paths_do_not_share_an_answer() {
        databaseHolds(List.of(1L), product(1L, "Panadol"));
        service.getRefs(List.of(1L));                       // warm

        databaseHolds(List.of(1L), product(1L, "Panadol Extra"));   // the row changed underneath
        assertThat(service.getRefs(List.of(1L), true))
                .extracting(ProductRef::getName).containsExactly("Panadol Extra");
    }

    // ── eviction: ALL THREE writes reach a ref ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a product write evicts the refs")
    void product_write_evicts() {
        databaseHolds(List.of(1L), product(1L, "Panadol"));
        service.getRefs(List.of(1L));

        refsCache.onProductsChanged(CatalogProductsChanged.of(ORG));

        service.getRefs(List.of(1L));
        verify(productRepository, times(2)).findAllByIdScoped(any(), any(), any());
    }

    @Test
    @DisplayName("⭐ a CATEGORY write evicts the refs — a ref carries the category NAME")
    void category_write_evicts() {
        Product withCategory = product(1L, "Panadol");
        withCategory.setCategory(Category.builder().id(9L).name("Tonics").organizationId(ORG).build());
        databaseHolds(List.of(1L), withCategory);

        assertThat(service.getRefs(List.of(1L)).get(0).getCategory()).isEqualTo("Tonics");

        refsCache.onCategoriesChanged(CatalogCategoriesChanged.of(ORG));

        service.getRefs(List.of(1L));
        // Without this the screens would show the OLD category name until the TTL, on every row of every grid.
        verify(productRepository, times(2)).findAllByIdScoped(any(), any(), any());
    }

    @Test
    @DisplayName("⭐ a TAX CODE write evicts the refs — resolveRate folds the code's rate into every ref")
    void tax_code_write_evicts() {
        databaseHolds(List.of(1L), product(1L, "Panadol"));
        service.getRefs(List.of(1L));

        refsCache.onTaxCodesChanged(CatalogTaxCodesChanged.of(ORG));

        service.getRefs(List.of(1L));
        verify(productRepository, times(2)).findAllByIdScoped(any(), any(), any());
    }

    // ── tenancy ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("another tenant never reads this tenant's cached refs")
    void refs_are_tenant_scoped() {
        databaseHolds(List.of(1L), product(1L, "Panadol"));
        service.getRefs(List.of(1L));

        TestTenant.authenticate(OTHER_ORG, USER);
        when(productRepository.findAllByIdScoped(eq(List.of(1L)), eq(OTHER_ORG), any())).thenReturn(List.of());

        assertThat(service.getRefs(List.of(1L))).isEmpty();
        verify(productRepository).findAllByIdScoped(eq(List.of(1L)), eq(OTHER_ORG), any());
    }

    @Test
    @DisplayName("an eviction for one tenant leaves another tenant's refs cached")
    void eviction_is_per_tenant() {
        databaseHolds(List.of(1L), product(1L, "Panadol"));
        service.getRefs(List.of(1L));

        refsCache.onProductsChanged(CatalogProductsChanged.of(OTHER_ORG));

        service.getRefs(List.of(1L));
        verify(productRepository, times(1)).findAllByIdScoped(any(), any(), any());
    }
}

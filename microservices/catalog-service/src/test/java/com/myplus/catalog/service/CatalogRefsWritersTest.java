package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.myplus.catalog.dto.CategoryDTO;
import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.dto.TaxCodeDTO;
import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.entity.TaxCode;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.repository.TaxCodeRepository;
import com.myplus.catalog.support.TestTenant;
import com.myplus.common.web.exception.ResourceNotFoundException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CACHE-2 — every writer of categories and tax codes publishes its event, and the three reads go through the cache.
 *
 * <p>The writers, found by the repository's TYPE (standard K4): {@code CategoryRepository} — 5 write sites in 3 classes;
 * {@code TaxCodeRepository} — 4 write sites in {@code TaxCodeService} (the 4th, apply's single-default rule, runs
 * inside create/update and is covered by their publish — case T2). Manufacturers have no writer of their own: they are
 * evicted by {@link CatalogProductsChanged}, whose 9 writers {@link ProductPickerWritersTest} already pins.
 */
@ExtendWith(MockitoExtension.class)
class CatalogRefsWritersTest {

    private static final Long ORG = 7L;
    private static final Long USER = 3L;
    private static final Long ID = 42L;

    @Mock private CategoryRepository categoryRepository;
    @Mock private TaxCodeRepository taxCodeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductPickerCache pickerCache;
    @Mock private CatalogRefsCache refsCache;
    @Mock private ApplicationEventPublisher events;

    private CategoryService categories;
    private TaxCodeService taxCodes;
    private ProductService products;

    @BeforeEach
    void setUp() {
        TestTenant.authenticate(ORG, USER);
        categories = new CategoryService(categoryRepository, refsCache, events);
        taxCodes = new TaxCodeService(taxCodeRepository, refsCache, events);
        products = new ProductService(productRepository, categoryRepository, taxCodeRepository, pickerCache, events,
                refsCache);
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    // ── the reads: through the cache, and on a miss the caller's tenant-scoped query ───────────────

    @Test
    @DisplayName("categories: cache-aside with the caller's tenant AND user; a miss runs the scoped query")
    void categories_read_through_the_cache() {
        missLoads(() -> when(refsCache.categories(eq(ORG), eq(USER), any())));
        when(categoryRepository.findScoped(ORG, USER)).thenReturn(List.of(category(ORG)));

        List<CategoryDTO> got = categories.getAll();

        assertThat(got).extracting(CategoryDTO::getName).containsExactly("Tonics");
    }

    @Test
    @DisplayName("tax codes: cache-aside with the caller's tenant AND user; a miss runs the scoped query")
    void tax_codes_read_through_the_cache() {
        missLoads(() -> when(refsCache.taxCodes(eq(ORG), eq(USER), any())));
        when(taxCodeRepository.findScoped(ORG, USER)).thenReturn(List.of(taxCode(ORG, ID, false)));

        List<TaxCodeDTO> got = taxCodes.list();

        assertThat(got).extracting(TaxCodeDTO::getName).containsExactly("GST");
    }

    @Test
    @DisplayName("manufacturers: cache-aside; the cached list is a COPY no caller can change")
    void manufacturers_read_through_the_cache() {
        missLoads(() -> when(refsCache.manufacturers(eq(ORG), eq(USER), any())));
        when(productRepository.findDistinctManufacturersScoped(ORG, USER)).thenReturn(new ArrayList<>(List.of("GSK")));

        List<String> got = products.manufacturers();

        assertThat(got).containsExactly("GSK");
        assertThatThrownBy(() -> got.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    // ── categories: 5 write sites in 3 classes ─────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 CategoryService.create → categories evicted")
    void category_create_publishes() {
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        categories.create(CategoryDTO.builder().name("Tonics").build());

        assertThat(onlyEvent()).isEqualTo(CatalogCategoriesChanged.of(ORG));
    }

    @Test
    @DisplayName("C2 CategoryService.update → categories evicted")
    void category_update_publishes() {
        when(categoryRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.of(category(ORG)));
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        categories.update(ID, CategoryDTO.builder().name("Syrups").build());

        assertThat(onlyEvent()).isEqualTo(CatalogCategoriesChanged.of(ORG));
    }

    @Test
    @DisplayName("C2 an update of another tenant's category is refused → nothing published (nothing was written)")
    void refused_category_update_publishes_nothing() {
        when(categoryRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categories.update(ID, CategoryDTO.builder().name("X").build()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("C3 CategoryService.delete → categories evicted")
    void category_delete_publishes() {
        Category c = category(ORG);
        when(categoryRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.of(c));

        categories.delete(ID);

        verify(categoryRepository).delete(c);
        assertThat(onlyEvent()).isEqualTo(CatalogCategoriesChanged.of(ORG));
    }

    @Test
    @DisplayName("C4 a product saved with a NEW free-text category → categories AND products evicted")
    void product_form_new_category_publishes() {
        when(categoryRepository.findByNameScoped("Tonics", ORG, USER)).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        products.create(ProductDTO.builder().name("Panadol").categoryName(" Tonics ").sellingPrice(BigDecimal.TEN).build());

        assertThat(events(2)).containsExactly(CatalogCategoriesChanged.of(ORG), CatalogProductsChanged.of(ORG));
    }

    @Test
    @DisplayName("C4 a product saved with an EXISTING category → only products evicted (the category list did not change)")
    void product_form_existing_category_publishes_only_products() {
        when(categoryRepository.findByNameScoped("Tonics", ORG, USER)).thenReturn(Optional.of(category(ORG)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        products.create(ProductDTO.builder().name("Panadol").categoryName("Tonics").sellingPrice(BigDecimal.TEN).build());

        assertThat(onlyEvent()).isEqualTo(CatalogProductsChanged.of(ORG));
    }

    @Test
    @DisplayName("C5 CSV import naming a NEW category (no transaction) → categories evicted")
    void import_new_category_publishes() {
        ProductImportSpec spec = importSpec();
        when(categoryRepository.findByNameScoped("Tonics", ORG, USER)).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        Category c = ReflectionTestUtils.invokeMethod(spec, "resolveCategory", " Tonics ", ORG, USER);

        assertThat(c.getName()).isEqualTo("Tonics");
        assertThat(onlyEvent()).isEqualTo(CatalogCategoriesChanged.of(ORG));
    }

    @Test
    @DisplayName("C5 CSV import naming an EXISTING category → nothing published")
    void import_existing_category_publishes_nothing() {
        ProductImportSpec spec = importSpec();
        when(categoryRepository.findByNameScoped("Tonics", ORG, USER)).thenReturn(Optional.of(category(ORG)));

        ReflectionTestUtils.invokeMethod(spec, "resolveCategory", "Tonics", ORG, USER);

        verify(categoryRepository, never()).save(any(Category.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    // ── tax codes: 4 write sites in TaxCodeService ─────────────────────────────────────────────────

    @Test
    @DisplayName("T1 TaxCodeService.create → tax codes evicted")
    void tax_code_create_publishes() {
        when(taxCodeRepository.save(any(TaxCode.class))).thenAnswer(i -> i.getArgument(0));

        taxCodes.create(TaxCodeDTO.builder().name("GST").rate(new BigDecimal("17")).build());

        assertThat(onlyEvent()).isEqualTo(CatalogTaxCodesChanged.of(ORG));
    }

    @Test
    @DisplayName("T2+T4 update making a new default clears the old default, and ONE eviction covers both rows")
    void tax_code_update_with_default_publishes_once() {
        TaxCode edited = taxCode(ORG, ID, false);
        TaxCode oldDefault = taxCode(ORG, 43L, true);
        when(taxCodeRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.of(edited));
        when(taxCodeRepository.findByOrganizationId(ORG)).thenReturn(List.of(edited, oldDefault));
        when(taxCodeRepository.save(any(TaxCode.class))).thenAnswer(i -> i.getArgument(0));

        taxCodes.update(ID, TaxCodeDTO.builder().name("GST").rate(new BigDecimal("18")).isDefault(true).build());

        verify(taxCodeRepository).save(oldDefault);
        assertThat(oldDefault.getIsDefault()).isFalse();
        assertThat(onlyEvent()).isEqualTo(CatalogTaxCodesChanged.of(ORG));
    }

    @Test
    @DisplayName("T2 an update of another tenant's code is refused → nothing published")
    void refused_tax_code_update_publishes_nothing() {
        when(taxCodeRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxCodes.update(ID, TaxCodeDTO.builder().name("X").build()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("T3 TaxCodeService.delete → tax codes evicted")
    void tax_code_delete_publishes() {
        TaxCode t = taxCode(ORG, ID, false);
        when(taxCodeRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.of(t));

        taxCodes.delete(ID);

        verify(taxCodeRepository).delete(t);
        assertThat(onlyEvent()).isEqualTo(CatalogTaxCodesChanged.of(ORG));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    /** Stub a cache read as a MISS: the cache runs the service's loader, so the test sees what the loader queries. */
    @SuppressWarnings("unchecked")
    private static <T> void missLoads(Supplier<org.mockito.stubbing.OngoingStubbing<List<T>>> stubbing) {
        stubbing.get().thenAnswer(i -> ((Supplier<List<T>>) i.getArgument(2)).get());
    }

    private ProductImportSpec importSpec() {
        ProductImportSpec spec = new ProductImportSpec();
        ReflectionTestUtils.setField(spec, "categoryRepository", categoryRepository);
        ReflectionTestUtils.setField(spec, "events", events);
        return spec;
    }

    private static Category category(Long org) {
        return Category.builder().id(ID).name("Tonics").organizationId(org).userId(USER).build();
    }

    private static TaxCode taxCode(Long org, Long id, boolean isDefault) {
        TaxCode t = new TaxCode();
        t.setId(id);
        t.setName("GST");
        t.setRate(new BigDecimal("17"));
        t.setOrganizationId(org);
        t.setUserId(USER);
        t.setIsDefault(isDefault);
        t.setActive(true);
        return t;
    }

    private Object onlyEvent() {
        return events(1).get(0);
    }

    private List<Object> events(int n) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events, times(n)).publishEvent(event.capture());
        return event.getAllValues();
    }
}

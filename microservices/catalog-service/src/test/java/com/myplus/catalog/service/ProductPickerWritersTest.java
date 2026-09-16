package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.myplus.catalog.controller.ProductPolicyAdminController;
import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.dto.ProductPickerDTO;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.BonusSchemeRepository;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.PriceRuleRepository;
import com.myplus.catalog.repository.ProductBarcodeRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.repository.TaxCodeRepository;
import com.myplus.catalog.support.TestTenant;
import com.myplus.common.web.exception.DuplicateResourceException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CACHE-1 — EVERY writer of {@code products} tells the picker cache, and a write that did not happen tells it nothing.
 *
 * <p>RULE 0's list, found by the repository's TYPE (9 write sites in 4 classes). The first pass searched by field name
 * and missed {@code ProductPolicyAdminController.products.saveAll}; a writer missing from this file is a picker that
 * serves the old row for up to the TTL. When a new writer is added, it is added here.
 *
 * <p>Pure Mockito, no Spring, no database: runs on every {@code mvn test}. The AFTER-COMMIT timing is proven separately,
 * through Spring's real listener machinery, in {@link ProductPickerCacheTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProductPickerWritersTest {

    private static final Long ORG = 7L;
    private static final Long USER = 3L;
    private static final Long ID = 42L;

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private TaxCodeRepository taxCodeRepository;
    @Mock private ProductPickerCache pickerCache;
    @Mock private ApplicationEventPublisher events;
    @Mock private CatalogRefsCache refsCache;   // CACHE-2 — its writers are in CatalogRefsWritersTest

    private ProductService service;

    @BeforeEach
    void setUp() {
        TestTenant.authenticate(ORG, USER);
        service = new ProductService(productRepository, categoryRepository, taxCodeRepository, pickerCache, events,
                refsCache);
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    // ── the read ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getPicker reads through the cache with the caller's tenant AND user")
    void picker_reads_through_the_cache() {
        Pageable pageable = PageRequest.of(0, 2000);
        Page<ProductPickerDTO> cached = new PageImpl<>(List.of());
        when(pickerCache.page(eq(ORG), eq(USER), eq(pageable), any())).thenReturn(cached);

        assertThat(service.getPicker(pageable)).isSameAs(cached);
    }

    // ── ProductService: writers 1–6 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1 create → the tenant is evicted")
    void create_publishes() {
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> withOrg(i.getArgument(0), ORG));

        service.create(ProductDTO.builder().name("Panadol").sellingPrice(new BigDecimal("12.50")).build());

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("1 create refused as a duplicate SKU → nothing published (nothing was written)")
    void refused_create_publishes_nothing() {
        when(productRepository.existsBySkuScoped("DUP", ORG, USER)).thenReturn(true);

        assertThatThrownBy(() -> service.create(ProductDTO.builder().name("X").sku("DUP").build()))
                .isInstanceOf(DuplicateResourceException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("2 update → evicted")
    void update_publishes() {
        found(product(ORG));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.update(ID, ProductDTO.builder().name("Panadol Extra").build());

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("3 setActive → evicted (a deactivated product must leave the picker)")
    void set_active_publishes() {
        found(product(ORG));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.setActive(ID, false);

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("4 updatePrice (the purchase path) → evicted — the cached row carries sellingPrice")
    void update_price_publishes() {
        found(product(ORG));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrice(ID, new BigDecimal("15.00"), null);

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("4 updatePrice carrying no rate changes nothing → nothing published")
    void no_op_update_price_publishes_nothing() {
        found(product(ORG));

        service.updatePrice(ID, null, null);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("5 updateClinicalFlags → evicted")
    void clinical_flags_publish() {
        found(product(ORG));

        service.updateClinicalFlags(ID, null, true);

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("6 updateTrackingFlags → evicted — the cached row carries requiresSerial")
    void tracking_flags_publish() {
        found(product(ORG));

        service.updateTrackingFlags(ID, null, null);

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("a product of another org seen through the scope → BOTH tenants are evicted")
    void both_orgs_are_evicted() {
        found(product(9L));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.setActive(ID, true);

        assertThat(publishedOrgs()).containsExactlyInAnyOrder(ORG, 9L);
    }

    // ── the other three classes: writers 7–9 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("7 ProductDeletionWriter.delete → evicted")
    void permanent_delete_publishes(
            @Mock ProductBarcodeRepository barcodes, @Mock PriceRuleRepository rules,
            @Mock BonusSchemeRepository schemes, @Mock CatalogAuditService audit) {
        Product inactive = product(ORG);
        inactive.setIsActive(false);   // PROD-DEL: only a deactivated product can be deleted
        found(inactive);

        new ProductDeletionWriter(productRepository, barcodes, rules, schemes, audit, events).delete(ID);

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("8 ProductImportSpec.persist (CSV import, no transaction) → evicted")
    void import_publishes() {
        ProductImportSpec spec = new ProductImportSpec();
        ReflectionTestUtils.setField(spec, "productRepository", productRepository);
        ReflectionTestUtils.setField(spec, "events", events);
        when(productRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(spec.persist(List.of(product(ORG)))).isEqualTo(1);

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("9 ProductPolicyAdminController.clearTrackingFlags (bulk) → evicted")
    void bulk_clear_publishes() {
        Product serialised = product(ORG);
        serialised.setRequiresSerial(true);
        when(productRepository.findRequiringSerial(ORG, USER)).thenReturn(List.of(serialised));

        new ProductPolicyAdminController(productRepository, events).clearTrackingFlags(null, "serialTracking");

        assertThat(publishedOrgs()).containsExactly(ORG);
    }

    @Test
    @DisplayName("9 a bulk clear that found nothing → nothing published")
    void empty_bulk_clear_publishes_nothing() {
        when(productRepository.findRequiringSerial(ORG, USER)).thenReturn(List.of());

        new ProductPolicyAdminController(productRepository, events).clearTrackingFlags(null, "serialTracking");

        verify(events, never()).publishEvent(any(Object.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static Product product(Long org) {
        Product p = new Product();
        p.setId(ID);
        p.setName("Panadol");
        p.setOrganizationId(org);
        p.setUserId(USER);
        p.setIsActive(true);
        p.setSellingPrice(new BigDecimal("12.50"));
        return p;
    }

    private static Product withOrg(Product p, Long org) {
        p.setOrganizationId(org);
        return p;
    }

    private void found(Product p) {
        when(productRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.of(p));
    }

    private Set<Long> publishedOrgs() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(CatalogProductsChanged.class);
        return ((CatalogProductsChanged) event.getValue()).orgs();
    }
}

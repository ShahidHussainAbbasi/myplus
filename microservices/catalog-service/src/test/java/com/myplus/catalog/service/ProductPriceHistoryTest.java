package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.entity.ProductPriceHistory;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductPriceHistoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.repository.TaxCodeRepository;
import com.myplus.catalog.support.TestTenant;

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
 * PR-1 — every writer of {@code Product.sellingPrice} leaves one history row when, and only when, the price CHANGED.
 *
 * <p>RULE 0's list (4 writers in 2 classes, found by {@code setSellingPrice} and the repository's save calls):
 * {@code create}, {@code update} (the product form), {@code updatePrice} (the purchase) and
 * {@code ProductImportSpec.persist} (CSV import — creates only). A writer missing from this file is a price that
 * moves with no trace.
 */
@ExtendWith(MockitoExtension.class)
class ProductPriceHistoryTest {

    private static final Long ORG = 7L;
    private static final Long USER = 3L;
    private static final Long ID = 42L;

    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private TaxCodeRepository taxCodeRepository;
    @Mock private ProductPickerCache pickerCache;
    @Mock private ApplicationEventPublisher events;
    @Mock private CatalogRefsCache refsCache;
    @Mock private ProductPriceHistoryRepository history;

    private ProductService service;

    @BeforeEach
    void setUp() {
        TestTenant.authenticate(ORG, USER);
        service = new ProductService(productRepository, categoryRepository, taxCodeRepository, pickerCache, events,
                refsCache, history);
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    @Test
    @DisplayName("1 create → one MANUAL row, old price empty, new = the price set")
    void create_records_the_opening_price() {
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> {
            Product p = i.getArgument(0);
            p.setId(ID);
            p.setOrganizationId(ORG);
            return p;
        });

        service.create(ProductDTO.builder().name("Panadol").sellingPrice(new BigDecimal("12.50")).build());

        ProductPriceHistory h = saved();
        assertThat(h.getSource()).isEqualTo(ProductPriceHistory.MANUAL);
        assertThat(h.getOldPrice()).isNull();
        assertThat(h.getNewPrice()).isEqualByComparingTo("12.50");
        assertThat(h.getOrganizationId()).isEqualTo(ORG);
        assertThat(h.getProductId()).isEqualTo(ID);
        assertThat(h.getChangedBy()).isEqualTo(USER);
        assertThat(h.getChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("2 update with a new price → one MANUAL row 12.50 → 14.00")
    void update_records_a_changed_price() {
        found(product("12.50"));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.update(ID, ProductDTO.builder().name("Panadol").sellingPrice(new BigDecimal("14.00")).build());

        ProductPriceHistory h = saved();
        assertThat(h.getSource()).isEqualTo(ProductPriceHistory.MANUAL);
        assertThat(h.getOldPrice()).isEqualByComparingTo("12.50");
        assertThat(h.getNewPrice()).isEqualByComparingTo("14.00");
    }

    @Test
    @DisplayName("2 update that leaves the price alone (12.50 vs 12.5) → NO row")
    void update_same_price_records_nothing() {
        found(product("12.50"));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.update(ID, ProductDTO.builder().name("Panadol Extra").sellingPrice(new BigDecimal("12.5")).build());

        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("3 updatePrice from a purchase → one PURCHASE row naming the purchase")
    void purchase_records_source_and_ref() {
        found(product("200.00"));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrice(ID, new BigDecimal("250.00"), new BigDecimal("210.00"), "PUR-000123");

        ProductPriceHistory h = saved();
        assertThat(h.getSource()).isEqualTo(ProductPriceHistory.PURCHASE);
        assertThat(h.getOldPrice()).isEqualByComparingTo("200.00");
        assertThat(h.getNewPrice()).isEqualByComparingTo("250.00");
        assertThat(h.getRef()).isEqualTo("PUR-000123");
    }

    @Test
    @DisplayName("3 updatePrice carrying ONLY the cost (KEEP mode) → cost stamped, price unchanged, NO row")
    void cost_only_purchase_records_nothing() {
        Product p = product("200.00");
        found(p);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrice(ID, null, new BigDecimal("210.00"), "PUR-000124");

        assertThat(p.getSellingPrice()).isEqualByComparingTo("200.00");
        assertThat(p.getLastPurchaseRate()).isEqualByComparingTo("210.00");
        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("3 updatePrice at the price it already has → NO row (a repeat purchase is not a price change)")
    void same_price_purchase_records_nothing() {
        found(product("250.00"));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrice(ID, new BigDecimal("250"), new BigDecimal("210.00"), "PUR-000125");

        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("4 CSV import → one IMPORT row per priced product; an unpriced one gets none")
    @SuppressWarnings("unchecked")
    void import_records_opening_prices() {
        ProductImportSpec spec = new ProductImportSpec();
        ReflectionTestUtils.setField(spec, "productRepository", productRepository);
        ReflectionTestUtils.setField(spec, "events", events);
        ReflectionTestUtils.setField(spec, "priceHistory", history);
        Product priced = product("99.00");
        Product unpriced = product(null);
        unpriced.setId(43L);
        when(productRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        spec.persist(List.of(priced, unpriced));

        ArgumentCaptor<List<ProductPriceHistory>> rows = ArgumentCaptor.forClass(List.class);
        verify(history).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        ProductPriceHistory h = rows.getValue().get(0);
        assertThat(h.getSource()).isEqualTo(ProductPriceHistory.IMPORT);
        assertThat(h.getProductId()).isEqualTo(ID);
        assertThat(h.getOldPrice()).isNull();
        assertThat(h.getNewPrice()).isEqualByComparingTo("99.00");
    }

    private ProductPriceHistory saved() {
        ArgumentCaptor<ProductPriceHistory> c = ArgumentCaptor.forClass(ProductPriceHistory.class);
        verify(history).save(c.capture());
        return c.getValue();
    }

    private static Product product(String price) {
        Product p = new Product();
        p.setId(ID);
        p.setName("Panadol");
        p.setOrganizationId(ORG);
        p.setUserId(USER);
        p.setIsActive(true);
        p.setSellingPrice(price == null ? null : new BigDecimal(price));
        return p;
    }

    private void found(Product p) {
        when(productRepository.findByIdScoped(ID, ORG, USER)).thenReturn(Optional.of(p));
    }

    // ── PR-2 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PR-2 updatePrice from the markup rule (Auto) → the history row says MARKUP, naming the bill")
    void markup_source_is_recorded() {
        found(product("200.00"));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrice(ID, new BigDecimal("241.50"), new BigDecimal("210.00"), "PUR-000126", "MARKUP");

        ProductPriceHistory h = saved();
        assertThat(h.getSource()).isEqualTo(ProductPriceHistory.MARKUP);
        assertThat(h.getRef()).isEqualTo("PUR-000126");
        assertThat(h.getNewPrice()).isEqualByComparingTo("241.50");
    }

    @Test
    @DisplayName("PR-2 any other source string is recorded as PURCHASE — a caller cannot invent a history source")
    void unknown_source_is_purchase() {
        found(product("200.00"));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrice(ID, new BigDecimal("250.00"), null, "PUR-000127", "MANUAL");

        assertThat(saved().getSource()).isEqualTo(ProductPriceHistory.PURCHASE);
    }

    @Test
    @DisplayName("PR-2 the product form round-trips its markup %; a cleared box (null) means the business's %")
    void markup_round_trips() {
        Product p = product("200.00");
        found(p);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        com.myplus.catalog.dto.ProductDTO out = service.update(ID, ProductDTO.builder().name("Panadol")
                .sellingPrice(new BigDecimal("200.00")).markupPct(new BigDecimal("14.5")).build());
        assertThat(p.getMarkupPct()).isEqualByComparingTo("14.50");
        assertThat(out.getMarkupPct()).isEqualByComparingTo("14.50");

        service.update(ID, ProductDTO.builder().name("Panadol").sellingPrice(new BigDecimal("200.00")).build());
        assertThat(p.getMarkupPct()).isNull();
    }

    @Test
    @DisplayName("PR-2 a markup outside 0–1000 % is refused with a sentence, never stored")
    void markup_range() {
        found(product("200.00"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(ID, ProductDTO.builder().name("Panadol")
                        .sellingPrice(new BigDecimal("200.00")).markupPct(new BigDecimal("-5")).build()))
                .isInstanceOf(com.myplus.common.web.exception.ValidationException.class)
                .hasMessageContaining("between 0 and 1000");
    }
}

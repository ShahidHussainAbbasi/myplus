package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductPriceHistoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.repository.TaxCodeRepository;
import com.myplus.catalog.support.TestTenant;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PR-2b — a category's markup %: its own writer (never through the general update, which rewrites name and parent),
 * scoped, validated, evicting the caches that carry it; and the product ref carries it for the purchase path.
 */
@ExtendWith(MockitoExtension.class)
class CategoryMarkupTest {

    private static final Long ORG = 7L;
    private static final Long USER = 3L;

    @Mock private CategoryRepository categoryRepository;
    @Mock private CatalogRefsCache refsCache;
    @Mock private ApplicationEventPublisher events;
    @Mock private ProductRepository productRepository;
    @Mock private TaxCodeRepository taxCodeRepository;
    @Mock private ProductPickerCache pickerCache;
    @Mock private ProductPriceHistoryRepository history;

    private CategoryService categories;

    @BeforeEach
    void setUp() {
        TestTenant.authenticate(ORG, USER);
        categories = new CategoryService(categoryRepository, refsCache, events);
    }

    @AfterEach
    void tearDown() { TestTenant.clear(); }

    private Category category() {
        Category c = Category.builder().id(5L).name("Medicines").description("Rx and OTC").organizationId(ORG).userId(USER).build();
        when(categoryRepository.findByIdScoped(5L, ORG, USER)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    @DisplayName("setMarkup sets ONLY the markup — name and description untouched — and evicts after commit")
    void sets_only_the_markup() {
        Category c = category();
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(categories.setMarkup(5L, new BigDecimal("20")).getMarkupPct()).isEqualByComparingTo("20.00");

        assertThat(c.getName()).isEqualTo("Medicines");
        assertThat(c.getDescription()).isEqualTo("Rx and OTC");
        verify(events).publishEvent(any(CatalogCategoriesChanged.class));   // evicts categories AND product refs
    }

    @Test
    @DisplayName("null clears it (the business's % applies again)")
    void null_clears() {
        Category c = category();
        c.setMarkupPct(new BigDecimal("20"));
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        categories.setMarkup(5L, null);

        assertThat(c.getMarkupPct()).isNull();
    }

    @Test
    @DisplayName("out of range → refused with a sentence, nothing saved or published")
    void range_refused() {
        category();
        assertThatThrownBy(() -> categories.setMarkup(5L, new BigDecimal("1001")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("between 0 and 1000");
        verify(categoryRepository, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("another tenant's category → not found (scoped lookup), never written")
    void other_tenant_not_found() {
        when(categoryRepository.findByIdScoped(9L, ORG, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> categories.setMarkup(9L, new BigDecimal("20"))).isInstanceOf(ResourceNotFoundException.class);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("the product ref carries its category's markup next to its own")
    void ref_carries_category_markup() {
        ProductService products = new ProductService(productRepository, categoryRepository, taxCodeRepository,
                pickerCache, events, refsCache, history);
        Category c = Category.builder().id(5L).name("Medicines").organizationId(ORG).markupPct(new BigDecimal("20.00")).build();
        Product p = new Product();
        p.setId(42L); p.setName("Panadol"); p.setOrganizationId(ORG); p.setCategory(c);
        p.setMarkupPct(new BigDecimal("30.00"));
        when(productRepository.findByIdScoped(42L, ORG, USER)).thenReturn(Optional.of(p));

        var ref = products.getRef(42L);

        assertThat(ref.getMarkupPct()).isEqualByComparingTo("30.00");
        assertThat(ref.getCategoryMarkupPct()).isEqualByComparingTo("20.00");
    }
}

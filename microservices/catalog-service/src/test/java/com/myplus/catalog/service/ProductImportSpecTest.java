package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.imports.ImportEngine;
import com.myplus.common.imports.ImportReport;

/**
 * Slice I2 — the Product spec: which columns exist, what a duplicate is, and what an imported row is built
 * with. Pure Mockito, no container, runs on every {@code mvn test}.
 *
 * <p>Driven through the real {@link ImportEngine} rather than by calling the spec's methods directly: the
 * behaviour worth pinning down is what an operator's FILE does, and the engine decides which spec method runs
 * for which row. Stubbing it out would test the parts and leave the wiring unexercised.
 */
class ProductImportSpecTest {

    private static final Long ORG = 1L, USER = 7L;

    private static final String HEADERS =
            "sku,name,description,categoryName,unit,manufacturer,sellingPrice,taxRate,barcode,"
            + "rxRequired,controlledSubstance\n";

    private ProductRepository productRepository;
    private CategoryRepository categoryRepository;
    private ProductImportSpec spec;
    private ImportEngine engine;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        spec = new ProductImportSpec();
        ReflectionTestUtils.setField(spec, "productRepository", productRepository);
        ReflectionTestUtils.setField(spec, "categoryRepository", categoryRepository);
        engine = new ImportEngine();

        when(productRepository.existingNamesScoped(any(), anyLong(), anyLong())).thenReturn(new ArrayList<>());
        when(productRepository.saveAll(any()))
                .thenAnswer(inv -> new ArrayList<>((Collection<Product>) inv.getArgument(0)));
        when(categoryRepository.findByNameScoped(any(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A valid row with everything after `name` blank. */
    private String minimal(String sku, String name) {
        return sku + "," + name + ",,,,,,,,,\n";
    }

    /** A row with a sku but NO name — invalid, since name carries the empty check. */
    private String blankName(String sku) {
        return minimal(sku, "");
    }

    private List<Product> captureSaved() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Product>> captor = ArgumentCaptor.forClass(List.class);
        verify(productRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ── the template ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void derived_rate_columns_are_not_offered() {
        List<String> headers = engine.templateHeaders(spec);

        // lastPurchaseRate/lastSaleRate/lastRateAt are STAMPED by the purchase and sale flows (slice 107).
        // They are facts about what has happened, not attributes — I1's dueAmount lesson in another costume.
        assertThat(headers).doesNotContain("lastPurchaseRate", "lastSaleRate", "lastRateAt");
        assertThat(headers).doesNotContain("id", "organizationId", "userId", "isActive");
        assertThat(headers).startsWith("sku", "name");
    }

    @Test
    void a_file_carrying_a_stamped_rate_column_is_refused_whole() {
        ImportReport r = engine.commit(spec, "sku,name,lastPurchaseRate\nA1,Widget,50\n", ORG, USER);

        assertThat(r.isCommitted()).isFalse();
        assertThat(r.getFileError()).contains("lastPurchaseRate");
        verify(productRepository, never()).saveAll(any());
    }

    // ── sku is required HERE even though the master allows it blank ─────────────────────────────────────────

    @Test
    void sku_is_OPTIONAL_because_the_master_allows_a_product_without_one() {
        ImportReport r = engine.dryRun(spec, HEADERS + minimal("", "Widget"), ORG, USER);

        // Reversed 2026-08-20: the import has no business being stricter than the screen it supplements.
        assertThat(r.getRefused()).isZero();
        assertThat(r.getToCreate()).isEqualTo(1);
    }

    @Test
    void name_is_required() {
        ImportReport r = engine.dryRun(spec, HEADERS + minimal("A1", ""), ORG, USER);

        assertThat(r.getRefused()).isEqualTo(1);
        assertThat(r.getRows().get(0).getMessage()).contains("'name'");
    }

    @Test
    void one_bad_row_stops_the_good_ones_being_written() {
        String csv = HEADERS + minimal("A1", "Widget") + blankName("A2") + minimal("A3", "Gadget");

        ImportReport r = engine.commit(spec, csv, ORG, USER);

        assertThat(r.isCommitted()).isFalse();
        verify(productRepository, never()).saveAll(any());
    }

    // ── per-column validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    void sellingPrice_and_taxRate_must_be_numbers() {
        ImportReport r = engine.dryRun(spec, HEADERS + "A1,Widget,,,,,cheap,,,,\n", ORG, USER);

        assertThat(r.getRefused()).isEqualTo(1);
        assertThat(r.getRows().get(0).getMessage()).contains("sellingPrice");
    }

    @Test
    void the_clinical_flags_only_accept_true_or_false() {
        ImportReport r = engine.dryRun(spec, HEADERS + "A1,Widget,,,,,,,,yes,\n", ORG, USER);

        assertThat(r.getRefused()).isEqualTo(1);
        assertThat(r.getRows().get(0).getMessage()).contains("rxRequired");
    }

    // ── duplicates ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_existing_NAME_is_skipped_and_the_rest_still_import() {
        when(productRepository.existingNamesScoped(any(), anyLong(), anyLong())).thenReturn(List.of("Widget"));

        ImportReport r = engine.commit(spec, HEADERS + minimal("A1", "Widget") + minimal("A2", "Gadget"),
                ORG, USER);

        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(r.getToCreate()).isEqualTo(1);
        assertThat(r.getRefused()).as("already existing is not a failure").isZero();
        assertThat(r.isCommitted()).isTrue();
    }

    @Test
    void the_existence_check_is_one_batched_call_for_the_whole_file() {
        StringBuilder sb = new StringBuilder(HEADERS);
        for (int i = 0; i < 25; i++) sb.append(minimal("SKU" + i, "Product " + i));

        engine.dryRun(spec, sb.toString(), ORG, USER);

        // existsBySkuScoped per row would be 25 round trips. This is the I1 rule carried across.
        verify(productRepository).existingNamesScoped(any(), anyLong(), anyLong());
    }

    @Test
    void a_NAME_repeated_inside_the_file_is_created_once() {
        ImportReport r = engine.commit(spec, HEADERS + minimal("A1", "Widget") + minimal("A2", "Widget"),
                ORG, USER);

        assertThat(r.getToCreate()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(r.getRows().get(1).getMessage()).contains("Appears earlier");
    }

    @Test
    void two_products_may_NOT_share_a_name_at_the_import_boundary() {
        // The master allows duplicate names (/name-check warns, it does not refuse), so this IS stricter
        // than the screen — deliberately, and in the safe direction. Name is the only field every row must
        // have, so it is the only thing that can serve as a duplicate key now that sku is optional; without
        // one, re-importing a corrected spreadsheet doubles the catalogue silently.
        //
        // A wrongly-skipped row is REPORTED and fixed by editing one cell. A wrongly-created one is silent
        // and surfaces weeks later at the till.
        ImportReport r = engine.commit(spec, HEADERS + minimal("A1", "Widget") + minimal("A2", "Widget"),
                ORG, USER);

        assertThat(r.getToCreate()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(r.getRefused()).as("a duplicate is skipped, not an error").isZero();
    }

    // ── what a built row contains ───────────────────────────────────────────────────────────────────────────

    @Test
    void an_imported_product_carries_the_file_values_and_the_servers_identity() {
        String csv = HEADERS + "SKU-9,Paracetamol,Box of 20,Analgesics,Box,Acme,120.50,17,8964000123456,true,false\n";

        engine.commit(spec, csv, ORG, USER);
        Product p = captureSaved().get(0);

        assertThat(p.getSku()).isEqualTo("SKU-9");
        assertThat(p.getName()).isEqualTo("Paracetamol");
        assertThat(p.getDescription()).isEqualTo("Box of 20");
        assertThat(p.getUnit()).isEqualTo("Box");
        assertThat(p.getManufacturer()).isEqualTo("Acme");
        assertThat(p.getSellingPrice()).isEqualByComparingTo(new BigDecimal("120.50"));
        assertThat(p.getTaxRate()).isEqualByComparingTo(new BigDecimal("17"));
        assertThat(p.getBarcode()).isEqualTo("8964000123456");
        assertThat(p.getRxRequired()).isTrue();
        assertThat(p.getControlledSubstance()).isFalse();

        assertThat(p.getOrganizationId()).isEqualTo(ORG);
        assertThat(p.getUserId()).isEqualTo(USER);
        assertThat(p.getId()).as("the id is the database's to assign").isNull();
    }

    @Test
    void an_imported_product_is_active_and_its_clinical_flags_default_to_false() {
        engine.commit(spec, HEADERS + minimal("A1", "Widget"), ORG, USER);
        Product p = captureSaved().get(0);

        assertThat(p.getIsActive()).as("a product is sellable the moment it lands").isTrue();
        // Blank must not make something prescription-only or controlled by omission.
        assertThat(p.getRxRequired()).isFalse();
        assertThat(p.getControlledSubstance()).isFalse();
    }

    // ── category ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_unknown_category_is_CREATED_rather_than_refused() {
        engine.commit(spec, HEADERS + "A1,Widget,,Analgesics,,,,,,,\n", ORG, USER);

        // The one deliberate exception to "refuse, never repair": a category is not the thing being imported,
        // and requiring thirty to be pre-created would make the feature unusable for its intended tenant.
        ArgumentCaptor<Category> cat = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(cat.capture());
        assertThat(cat.getValue().getName()).isEqualTo("Analgesics");
        assertThat(cat.getValue().getOrganizationId()).isEqualTo(ORG);
    }

    @Test
    void an_existing_category_is_reused_not_duplicated() {
        Category existing = Category.builder().id(5L).name("Analgesics").organizationId(ORG).build();
        when(categoryRepository.findByNameScoped(any(), anyLong(), anyLong())).thenReturn(Optional.of(existing));

        engine.commit(spec, HEADERS + "A1,Widget,,Analgesics,,,,,,,\n", ORG, USER);

        verify(categoryRepository, never()).save(any());
        assertThat(captureSaved().get(0).getCategory()).isSameAs(existing);
    }

    @Test
    void a_blank_category_leaves_the_product_uncategorised() {
        engine.commit(spec, HEADERS + minimal("A1", "Widget"), ORG, USER);

        verify(categoryRepository, never()).save(any());
        assertThat(captureSaved().get(0).getCategory()).isNull();
    }

    // ── scope ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_duplicate_check_is_scoped_to_the_calling_tenant() {
        engine.dryRun(spec, HEADERS + minimal("A1", "Widget"), ORG, USER);

        // Anti-IDOR at the read: the scope comes from the authenticated caller, never from the file.
        verify(productRepository).existingNamesScoped(any(),
                org.mockito.ArgumentMatchers.eq(ORG), org.mockito.ArgumentMatchers.eq(USER));
    }
}

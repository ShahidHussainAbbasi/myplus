package com.myplus.catalog.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.imports.ColumnSpec;
import com.myplus.common.imports.CsvReader;
import com.myplus.common.imports.ImportSpec;

/**
 * Slice I2 — what a Product CSV import consists of.
 *
 * <h3>It lives HERE, in the service that owns the master</h3>
 * The alternative was to keep both specs in business-service and write products through {@code CatalogClient}.
 * Rejected: business-service would own the validation rules for a table it does not store, every duplicate
 * check would become a remote call, and it would re-create exactly the coupling decomposition exists to
 * prevent. The import engine is a library, so the spec can sit beside its data at no cost.
 *
 * <h3>SKU is OPTIONAL; NAME carries both the empty check and the duplicate check</h3>
 * The first cut required a SKU, on the numbers: of 1 581 live products only 10 (0.6%) lack one, while 1 543
 * (97.6%) lack a barcode. <b>Reversed on 2026-08-20 at the user's direction</b> — the master permits a
 * product with no SKU and the import had no business being stricter than the screen it supplements.
 *
 * <p>What that requirement was really protecting is the duplicate check, and that is kept — moved onto
 * {@code name}, which is the one field every row must have. Without ANY key a row is re-created on every
 * import of the same file, so a shopkeeper re-uploading a corrected spreadsheet silently doubles the
 * products in it, and nobody notices until the till shows two of everything.
 *
 * <p><b>The consequence, stated plainly:</b> two genuinely different products that share a name cannot both
 * be imported — the second is reported as already present. The master itself allows duplicate names
 * ({@code /name-check} warns rather than refuses), so this IS stricter than the screen. It is stricter in
 * the safe direction: a wrongly-skipped row is reported to the operator and fixed by editing one cell,
 * whereas a wrongly-created one is silent and found weeks later at the till.
 *
 * <h3>What is NOT importable</h3>
 * {@code lastPurchaseRate}, {@code lastSaleRate} and {@code lastRateAt} are <b>stamped by the purchase and
 * sale flows</b> (slice 107). They are derived facts about what has happened, not attributes of the product.
 * Accepting them from a spreadsheet would be I1's {@code dueAmount} mistake in a different costume: a number
 * in a cell with no transaction behind it. A file carrying one is refused whole, never quietly stripped.
 *
 * <p>{@code isActive} is not offered either — every imported product is active. Deactivating is an action
 * taken about a product that exists, not a property to seed it with.
 */
@Component
public class ProductImportSpec implements ImportSpec<Product> {

    static final String SKU = "sku";
    static final String NAME = "name";
    static final String DESCRIPTION = "description";
    static final String CATEGORY = "categoryName";
    static final String UNIT = "unit";
    static final String MANUFACTURER = "manufacturer";
    static final String SELLING_PRICE = "sellingPrice";
    static final String TAX_RATE = "taxRate";
    static final String BARCODE = "barcode";
    static final String RX_REQUIRED = "rxRequired";
    static final String CONTROLLED = "controlledSubstance";
    /*
     * U9 - the pack rules, importable.
     *
     * A pharmacy switching on loose selling has to say what a pack holds for every medicine it splits.
     * Doing that by hand on a 1,200-product catalogue is the reason a shop does not adopt the feature at
     * all - so the columns that decide it belong in the file that creates the products.
     *
     * `defaultSellUnit` is deliberately NOT importable: it is a per-till preference with a safe default
     * (PACK) and one more column to get wrong on the sheet that matters most.
     */
    static final String PACK_SIZE = "packSize";
    static final String LOOSE_UNIT = "looseUnit";
    static final String LOOSE_UNIT_PLURAL = "looseUnitPlural";
    static final String ALLOW_LOOSE = "allowLoose";

    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Override public String entity() { return "product"; }

    @Override public String label() { return "Products"; }

    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(
                ColumnSpec.text(SKU, false, 255, "SKU-1001"),
                ColumnSpec.text(NAME, true, 255, "Paracetamol 500mg"),
                ColumnSpec.text(DESCRIPTION, false, 2000, "Box of 20 tablets"),
                ColumnSpec.text(CATEGORY, false, 255, "Analgesics"),
                ColumnSpec.text(UNIT, false, 60, "Box"),
                ColumnSpec.text(MANUFACTURER, false, 255, "Acme Pharma"),
                ColumnSpec.number(SELLING_PRICE, false, "120.00"),
                ColumnSpec.number(TAX_RATE, false, "17"),
                ColumnSpec.text(BARCODE, false, 255, "8964000123456"),
                ColumnSpec.oneOf(RX_REQUIRED, false, "true", "false"),
                ColumnSpec.oneOf(CONTROLLED, false, "true", "false"),
                ColumnSpec.integer(PACK_SIZE, false, "10"),
                ColumnSpec.text(LOOSE_UNIT, false, 32, "tablet"),
                ColumnSpec.text(LOOSE_UNIT_PLURAL, false, 32, "tablets"),
                ColumnSpec.oneOf(ALLOW_LOOSE, false, "true", "false"));
    }

    /**
     * The product NAME — the only field every row is required to have.
     *
     * <p>Not the SKU: that became optional on 2026-08-20, and a key that is sometimes absent is not a key.
     * A row with no key is re-created on every import of the same file.
     *
     * <p>Not case-folded or whitespace-stripped: the key must be comparable to its column by a plain
     * {@code IN} or the batched lookup degrades into the scan it exists to replace ({@code idx_products_org_name},
     * V10, indexes the exact column). So "Widget" and "widget" are different products here — the same
     * comparison the rest of the application makes.
     */
    @Override
    public String duplicateKey(CsvReader.Row row) {
        return row.get(NAME);               // Row.get already trims, and maps blank to null
    }

    /**
     * Which of these product names the tenant already has — ONE batched query for the whole file.
     *
     * <p>Never one query per row: the single-row {@code existsBySkuScoped} is right for one save and would
     * be O(n) round trips here, which is the shape {@code addCustomer}'s in-memory scan already has.
     * Served by {@code idx_products_org_name} (V10).
     */
    @Override
    public Set<String> existingKeys(Long orgId, Long userId, Set<String> keys) {
        List<String> stored = productRepository.existingNamesScoped(new ArrayList<>(keys), orgId, userId);
        Set<String> hit = new HashSet<>();
        for (String s : stored) {
            if (s == null) continue;
            String trimmed = s.trim();
            if (keys.contains(trimmed)) hit.add(trimmed);
        }
        return hit;
    }

    @Override
    public Product build(CsvReader.Row row, Long orgId, Long userId) {
        String price = row.get(SELLING_PRICE);
        String tax = row.get(TAX_RATE);
        LocalDateTime now = LocalDateTime.now();

        return Product.builder()
                .sku(row.get(SKU))
                .name(row.get(NAME))
                .description(row.get(DESCRIPTION))
                .unit(row.get(UNIT))
                .manufacturer(row.get(MANUFACTURER))
                .barcode(row.get(BARCODE))
                .sellingPrice(price == null ? null : new BigDecimal(price.replace(",", "")))
                .taxRate(tax == null ? null : new BigDecimal(tax.replace(",", "")))
                .category(resolveCategory(row.get(CATEGORY), orgId, userId))
                .rxRequired(bool(row.get(RX_REQUIRED)))
                .controlledSubstance(bool(row.get(CONTROLLED)))
                // U9. A blank packSize is NOT "make this indivisible" - it is "not supplied", which is the
                // same rule the product form follows so that a file written before this feature existed
                // cannot strip the pack rules off the products it touches.
                .packSize(intOrNull(row.get(PACK_SIZE)))
                .looseUnit(trimToNull(row.get(LOOSE_UNIT)))
                .looseUnitPlural(trimToNull(row.get(LOOSE_UNIT_PLURAL)))
                .allowLoose(bool(row.get(ALLOW_LOOSE)))
                // Every imported product is sellable from the moment it lands. Deactivation is an action about
                // a product that exists, not a state to seed.
                .isActive(true)
                .organizationId(orgId)
                .userId(userId)                 // audit: who created the row
                .createdBy(userId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Blank means false — the safe default for both flags: nothing becomes prescription-only by omission. */
    private static Boolean bool(String v) {
        return v != null && Boolean.parseBoolean(v.trim());
    }

    private static Integer intOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Integer.valueOf(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * U9 — the one rule that spans two columns: <b>"may be sold loose" needs something to divide.</b>
     *
     * <p>{@code allowLoose=true} with no pack size is a contradiction the product form cannot produce — it
     * hides the loose fields until a pack size above 1 is entered, and unticks the box when one is cleared.
     * An import must not be a way around that, or a shop ends up with products marked splittable that the
     * till then refuses to split, and nothing explains why.
     *
     * <p><b>Refused with a reason rather than silently coerced.</b> Quietly turning the flag off would import
     * the file "successfully" and leave the operator believing 1,200 products were configured when some were
     * not — and the engine refuses the whole file on any bad row, so they are told before anything is written.
     */
    @Override
    public String validateRow(CsvReader.Row row) {
        boolean wantsLoose = bool(row.get(ALLOW_LOOSE));
        if (!wantsLoose) return null;
        Integer packSize = intOrNull(row.get(PACK_SIZE));
        if (packSize == null || packSize <= 1) {
            return "allowLoose is true but packSize is " + (packSize == null ? "missing" : packSize)
                    + " — a product can only be sold by the piece if a pack holds more than one.";
        }
        return null;
    }

    /**
     * Find-or-create the tenant's category by name.
     *
     * <p>Create rather than refuse, and this is the one place the slice's "refuse, never repair" rule does not
     * apply — deliberately. A category is not the thing being imported; it is a grouping the product names,
     * and requiring an operator to pre-create thirty categories before their first upload would make the
     * feature unusable for exactly the tenant it exists for. Nothing is guessed or corrected: the category is
     * created with the name as written.
     */
    private Category resolveCategory(String name, Long orgId, Long userId) {
        if (name == null) return null;
        String trimmed = name.trim();
        return categoryRepository.findByNameScoped(trimmed, orgId, userId)
                .orElseGet(() -> categoryRepository.save(
                        Category.builder().name(trimmed).organizationId(orgId).userId(userId).build()));
    }

    @Override
    public int persist(List<Product> batch) {
        return productRepository.saveAll(batch).size();
    }
}

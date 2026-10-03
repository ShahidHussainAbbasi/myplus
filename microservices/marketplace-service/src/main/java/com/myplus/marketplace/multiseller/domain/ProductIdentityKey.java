package com.myplus.marketplace.multiseller.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The composite product identity key (source §6).
 *
 * <p>Two seller products are candidates for the same canonical product only when every identity attribute agrees.
 * A name is never enough: "Samsung A32" at 64GB and at 128GB, or Panadol in packs of 10 and 20, are different
 * products and produce different keys. The key only PROPOSES a match; a person confirms it (match review, MKT-1b).
 *
 * <pre>
 *   SAMSUNG|GALAXY-A32|128GB|BLACK|NEW|WARRANTY-12M
 *   GSK|PANADOL-EXTRA|500MG|TABLET|10
 * </pre>
 *
 * <p>Normalisation is deliberately conservative: case, accents, surrounding/inner whitespace and the space between
 * a number and its unit ("128 GB" = "128GB"). It never maps synonyms ("Blk" ≠ "Black") — a wrong merge costs more
 * than a missed one, because a missed one is caught in review and a wrong one is sold.
 */
public final class ProductIdentityKey {

    public static final String SEPARATOR = "|";

    private ProductIdentityKey() {
    }

    /** General goods. Brand and model are required; the rest are included when present, in a fixed order. */
    public static String general(String brand, String model, String variant, String colour, String size,
            String unit, String packSize, String condition, String warrantyType) {
        require("brand", brand);
        require("model", model);
        return join(brand, model, variant, colour, size, unit, packSize, condition, warrantyType);
    }

    /** Medicines (Phase 6): every part is required — a strength or pack size cannot be "unknown". */
    public static String medicine(String brand, String productName, String strength, String dosageForm,
            String packSize) {
        require("brand", brand);
        require("product name", productName);
        require("strength", strength);
        require("dosage form", dosageForm);
        require("pack size", packSize);
        return join(brand, productName, strength, dosageForm, packSize);
    }

    /**
     * A GTIN is authoritative when it is present and well formed (8, 12, 13 or 14 digits with a valid check digit);
     * otherwise the composite key stands. A malformed barcode is ignored rather than trusted — a typo would
     * otherwise merge two unrelated products.
     */
    public static String preferGtin(String gtin, String compositeKey) {
        String digits = gtin == null ? "" : gtin.replaceAll("\\s", "");
        return isValidGtin(digits) ? "GTIN:" + digits : compositeKey;
    }

    /** GS1 check digit over GTIN-8/12/13/14. */
    public static boolean isValidGtin(String digits) {
        if (digits == null || !digits.matches("\\d{8}|\\d{12}|\\d{13}|\\d{14}")) return false;
        int sum = 0;
        int len = digits.length();
        for (int i = 0; i < len - 1; i++) {
            int d = digits.charAt(len - 2 - i) - '0';
            sum += (i % 2 == 0) ? d * 3 : d;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == digits.charAt(len - 1) - '0';
    }

    /** Units of measure a number may be written apart from. Storage, mass, volume, length, power, frequency, pack. */
    static final String UNITS = "TB|GB|MB|KB|MCG|MG|KG|G|ML|L|MM|CM|M|IN|MAH|KW|W|V|GHZ|MHZ|HZ|MP|PCS|PC|TABS|TAB|CAPS|CAP";

    public static String normalize(String part) {
        if (part == null) return "";
        String s = Normalizer.normalize(part, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.trim().toUpperCase(Locale.ROOT).replace(SEPARATOR, "-").replaceAll("\\s+", " ");
        // A number and its UNIT are one token: "128 GB" -> "128GB", "500 MG" -> "500MG". Only listed units — gluing a
        // number to ANY following word made "Galaxy A32 Pro" read "A32PRO" and "iPhone 15 Pro" read "15PRO", which
        // is the opposite of conservative (found by the MKT-1b gate on a live stack).
        s = s.replaceAll("(\\d)\\s+(" + UNITS + ")(?![A-Z0-9])", "$1$2");   // a unit ENDS a word: "A32 W70934" is a model, not watts
        return s.replace(' ', '-');
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String n = normalize(p);
            if (!n.isEmpty()) out.add(n);
        }
        return String.join(SEPARATOR, out);
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank())
            throw new MarketplaceRuleException("IDENTITY_INCOMPLETE",
                    "The " + name + " is required to match this product.");
    }
}

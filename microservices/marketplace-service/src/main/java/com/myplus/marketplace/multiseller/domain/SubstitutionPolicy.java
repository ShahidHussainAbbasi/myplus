package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * When a shortage may be resolved without asking the customer (source §11).
 *
 * <p>Never without approval: a different strength, size, variant, colour, pack size or brand — or a higher price or
 * a later delivery (§11 step 6). Same-product reassignment to another seller at the same or lower price and the
 * same or earlier promise is the only silent move, and it is what REASSIGNED means.
 */
public final class SubstitutionPolicy {

    /** Attribute names compared case-insensitively against the canonical product's variant attributes. */
    public static final Set<String> PROTECTED = Set.of("strength", "size", "variant", "storage", "colour", "color",
            "packsize", "brand");

    private SubstitutionPolicy() {
    }

    public static boolean requiresCustomerApproval(Map<String, String> original, Map<String, String> substitute,
            BigDecimal originalPrice, BigDecimal substitutePrice, int originalPromiseHours, int substitutePromiseHours) {
        for (String attr : PROTECTED) {
            if (!Objects.equals(norm(find(original, attr)), norm(find(substitute, attr)))) return true;
        }
        if (substitutePrice.compareTo(originalPrice) > 0) return true;
        return substitutePromiseHours > originalPromiseHours;
    }

    private static String find(Map<String, String> attrs, String key) {
        if (attrs == null) return null;
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (e.getKey() != null && e.getKey().replaceAll("[\\s_-]", "").equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static String norm(String v) {
        return v == null ? null : ProductIdentityKey.normalize(v).toLowerCase(Locale.ROOT);
    }
}

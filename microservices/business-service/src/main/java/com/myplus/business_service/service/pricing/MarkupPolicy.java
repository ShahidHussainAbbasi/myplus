package com.myplus.business_service.service.pricing;

import java.math.BigDecimal;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.myplus.common.settings.SettingsService;

import lombok.RequiredArgsConstructor;

/**
 * PR-2 — the business's markup rule, read from Settings → Purchasing and the product's own markup %, and what it
 * suggests for one purchase cost. Precedence: the product's % > its category's % (PR-2b) > the business's %.
 *
 * <p>Answers a {@link Suggestion} rather than a bare price, because the purchase form has to say WHY: which % (the
 * product's or the business's), and — in Auto — whether a guard held the price back (it would lower it, or raise it
 * past the cap). Settings that cannot be read resolve to OFF: a settings outage must never re-price a product.
 */
@Service
@RequiredArgsConstructor
public class MarkupPolicy {

    public static final String MODE_KEY = "pos.pricing.markupMode";
    public static final String BASIS_KEY = "pos.pricing.markupBasis";
    public static final String PCT_KEY = "pos.pricing.markupPct";
    public static final String ROUNDING_KEY = "pos.pricing.markupRounding";
    public static final String NEVER_LOWER_KEY = "pos.pricing.markupNeverLower";
    public static final String MAX_RISE_KEY = "pos.pricing.markupMaxRisePct";

    /** Values are LOWER-case: getChoice lower-cases what is stored (see PurchaseService.PRICE_MODE_KEY). */
    public static final String OFF = "off";
    public static final String SUGGEST = "suggest";
    public static final String AUTO = "auto";
    /** PR-4 — a purchase never moves the price itself; the price it would set waits for the owner's approval. */
    public static final String APPROVAL = "approval";
    /** Every value the mode may hold. BOTH readers below list it: a value missing from one silently reads as Suggest. */
    public static final Set<String> MODES = Set.of(OFF, SUGGEST, AUTO, APPROVAL);

    public static final String GUARD_NEVER_LOWER = "NEVER_LOWER";
    public static final String GUARD_MAX_RISE = "MAX_RISE";

    private final SettingsService settings;

    /** What the rule says for one cost. {@code price} is null when there is no rule (no %, or mode off). */
    public record Suggestion(String mode, String basis, String rounding, BigDecimal pct, String pctSource,
                             BigDecimal cost, BigDecimal raw, BigDecimal price, BigDecimal current, String guard) {
        /** True when Auto would set this price on save: a rule price exists and no guard holds it back. */
        public boolean autoApplies() { return AUTO.equals(mode) && price != null && guard == null; }

        /** PR-4 — what the purchase form and the queue say about why this price: "14.5% on cost, the business rate". */
        public String detail() {
            if (pct == null || price == null) return null;
            String of = "PRODUCT".equals(pctSource) ? "this product's own" : "CATEGORY".equals(pctSource) ? "this category's"
                    : "the business rate";
            return pct.stripTrailingZeros().toPlainString() + "% " + ("margin".equals(basis) ? "margin" : "on cost") + ", " + of;
        }
    }

    public String mode() {
        try {
            return settings.getChoice(MODE_KEY, MODES, SUGGEST);
        } catch (RuntimeException unreadable) {
            return OFF;
        }
    }

    /**
     * The suggestion for {@code cost}. {@code productPct} is the product's own markup (null = the business's);
     * {@code current} is the product's selling price now (for the guards and the hint).
     */
    public Suggestion suggest(BigDecimal cost, BigDecimal productPct, BigDecimal current) {
        return suggest(cost, productPct, null, current);
    }

    /** PR-2b — with the product's category's % between the product's own and the business's. */
    public Suggestion suggest(BigDecimal cost, BigDecimal productPct, BigDecimal categoryPct, BigDecimal current) {
        String mode;
        String basis;
        String rounding;
        BigDecimal businessPct;
        boolean neverLower;
        int maxRise;
        try {
            mode = settings.getChoice(MODE_KEY, MODES, SUGGEST);
            basis = settings.getChoice(BASIS_KEY, Set.of(MarkupCalculator.MARKUP, MarkupCalculator.MARGIN), MarkupCalculator.MARKUP);
            rounding = settings.getChoice(ROUNDING_KEY, Set.of(MarkupCalculator.EXACT, MarkupCalculator.UP1,
                    MarkupCalculator.NEAR5, MarkupCalculator.NEAR10), MarkupCalculator.EXACT);
            businessPct = settings.getDecimal(PCT_KEY, BigDecimal.ZERO);
            neverLower = settings.getBool(NEVER_LOWER_KEY);   // override, else the catalog default (on)
            maxRise = settings.getInt(MAX_RISE_KEY, 0);
        } catch (RuntimeException unreadable) {
            return new Suggestion(OFF, null, null, null, null, cost, null, null, current, null);
        }
        boolean own = productPct != null && productPct.signum() > 0;
        boolean cat = !own && categoryPct != null && categoryPct.signum() > 0;
        BigDecimal pct = own ? productPct : cat ? categoryPct : businessPct;
        String source = own ? "PRODUCT" : cat ? "CATEGORY" : "BUSINESS";
        if (OFF.equals(mode) || pct == null || pct.signum() <= 0 || cost == null || cost.signum() <= 0) {
            return new Suggestion(mode, basis, rounding, pct, source, cost, null, null, current, null);
        }
        BigDecimal raw;
        try {
            raw = MarkupCalculator.raw(cost, pct, basis);
        } catch (IllegalArgumentException impossible) {   // a margin of 100% or more
            return new Suggestion(mode, basis, rounding, pct, source, cost, null, null, current, null);
        }
        BigDecimal price = MarkupCalculator.round(raw, rounding);
        String guard = null;
        if (current != null && current.signum() > 0) {
            if (neverLower && price.compareTo(current) < 0) guard = GUARD_NEVER_LOWER;
            else if (maxRise > 0 && price.compareTo(current.multiply(BigDecimal.ONE.add(
                    BigDecimal.valueOf(maxRise).movePointLeft(2)))) > 0) guard = GUARD_MAX_RISE;
        }
        return new Suggestion(mode, basis, rounding, pct, source, cost, raw, price, current, guard);
    }
}

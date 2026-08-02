package com.myplus.commerce.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves what a given buyer pays for each line of a basket — B2B Phase 2 (= OMS B1, requirement #10).
 *
 * <h3>The rule</h3>
 * <pre>
 *   1. customer × product    (most specific)
 *   2. customer × category
 *   3. type     × product
 *   4. type     × category
 *   5. catalog price         (no rule matched)
 * </pre>
 *
 * <p><b>Rules never stack.</b> Exactly one rule applies to a line: the most specific live one. Stacking is how
 * a 10% and a 5% rule quietly become 14.5% and nobody can explain the invoice — and an invoice a shopkeeper
 * cannot explain to their customer is worse than no discount at all. Every result carries the rule that set
 * it, so any price can be justified after the fact.
 *
 * <h3>Pure by design</h3>
 * No repository, no clock, no Spring. The caller supplies the tenant's rules (already org-scoped by the owning
 * service, so this code cannot get scoping wrong) and the date to judge them against. The precedence table
 * above is the part most likely to be argued about, and this shape makes it testable without a context —
 * the same reasoning that made {@code CreditLimitPolicy} pure in Phase 1.
 */
public final class PriceResolver {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PriceResolver() {
    }

    /**
     * Price every line. Never throws for business reasons and never returns fewer lines than it was given:
     * a line with no applicable rule comes back at its catalog price, because a pricing decision must always
     * produce an answer the till can act on.
     */
    public static List<PricedLine> resolve(List<PriceRule> rules, PricingContext ctx, List<BasketLine> lines) {
        List<PricedLine> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (BasketLine line : lines) {
            out.add(resolveOne(rules, ctx, line));
        }
        return out;
    }

    /** Price one line. Exposed for callers that price a single item (the sell screen's live preview). */
    public static PricedLine resolveOne(List<PriceRule> rules, PricingContext ctx, BasketLine line) {
        if (line == null) {
            return null;
        }
        BigDecimal catalog = nz(line.catalogPrice());
        PriceRule best = bestRule(rules, ctx, line);
        if (best == null) {
            return PricedLine.catalog(line.productId(), catalog);
        }
        BigDecimal price = apply(best, catalog);
        if (price == null) {
            // A rule that cannot produce a sane price is treated as absent rather than allowed to poison the
            // line. Falling back to catalog is always safe; a negative or nonsensical price is not.
            return PricedLine.catalog(line.productId(), catalog);
        }
        String source = best.getScope() == PriceRule.Scope.CUSTOMER ? PricedLine.CONTRACT : PricedLine.TIER;
        return new PricedLine(line.productId(), price, source, best.getId(), reasonFor(best));
    }

    /**
     * The single rule that applies to this line, or null.
     *
     * <p>Ordering is total and deterministic: specificity, then the owner's explicit {@code priority}, then
     * the lowest id. The last tie-break matters more than it looks — without it, two equally-specific rules
     * would resolve by whatever order the database happened to return, and the same basket could price
     * differently between two tills.
     */
    public static PriceRule bestRule(List<PriceRule> rules, PricingContext ctx, BasketLine line) {
        if (rules == null || rules.isEmpty() || line == null) {
            return null;
        }
        java.time.LocalDate on = (ctx == null) ? null : ctx.on();
        return rules.stream()
                .filter(r -> r != null && r.isLiveOn(on))
                .filter(r -> matches(r, ctx, line))
                .max(Comparator.comparingInt(PriceRule::specificity)
                        .thenComparingInt(PriceRule::getPriority)
                        // max() on the NEGATED id => the LOWEST id wins. A null id (an unsaved rule) must
                        // LOSE every tie, hence MIN_VALUE — MAX_VALUE would let it beat every real rule.
                        .thenComparing(r -> r.getId() == null ? Long.MIN_VALUE : -r.getId()))
                .orElse(null);
    }

    private static boolean matches(PriceRule r, PricingContext ctx, BasketLine line) {
        // WHO
        if (r.getScope() == PriceRule.Scope.CUSTOMER) {
            if (ctx == null || ctx.customerId() == null || r.getCustomerId() == null
                    || !r.getCustomerId().equals(ctx.customerId())) {
                return false;
            }
        } else if (r.getScope() == PriceRule.Scope.TYPE) {
            if (ctx == null || ctx.customerType() == null || r.getCustomerType() == null
                    || !r.getCustomerType().equalsIgnoreCase(ctx.customerType())) {
                return false;
            }
        } else {
            return false;
        }
        // WHAT
        if (r.getTarget() == PriceRule.Target.PRODUCT) {
            return r.getProductId() != null && r.getProductId().equals(line.productId());
        }
        if (r.getTarget() == PriceRule.Target.CATEGORY) {
            return r.getCategoryId() != null && r.getCategoryId().equals(line.categoryId());
        }
        return false;
    }

    /**
     * Turn a rule into a unit price, or null when the rule is not usable.
     *
     * <p>A FIXED 0 is honoured — a giveaway is a legitimate price and must not be mistaken for "unset".
     * A PERCENT outside 0-100 is rejected rather than allowed to produce a negative price or a markup that
     * nobody meant.
     */
    static BigDecimal apply(PriceRule rule, BigDecimal catalogPrice) {
        BigDecimal v = rule.getValue();
        if (v == null) {
            return null;
        }
        if (rule.getMode() == PriceRule.Mode.FIXED) {
            return v.signum() < 0 ? null : v.setScale(2, RoundingMode.HALF_UP);
        }
        if (rule.getMode() == PriceRule.Mode.PERCENT) {
            if (v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
                return null;
            }
            BigDecimal keep = HUNDRED.subtract(v);
            return nz(catalogPrice).multiply(keep).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        return null;
    }

    /** A short explanation for the receipt and the sell screen — never null, because a price without a reason is the problem being fixed. */
    static String reasonFor(PriceRule r) {
        String who = (r.getScope() == PriceRule.Scope.CUSTOMER)
                ? "Contract price"
                : ((r.getCustomerType() == null ? "Tier" : titleCase(r.getCustomerType())) + " price");
        if (r.getMode() == PriceRule.Mode.PERCENT && r.getValue() != null) {
            return who + " −" + r.getValue().stripTrailingZeros().toPlainString() + "%";
        }
        return who;
    }

    private static String titleCase(String s) {
        String t = s.replace('_', ' ').trim().toLowerCase();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

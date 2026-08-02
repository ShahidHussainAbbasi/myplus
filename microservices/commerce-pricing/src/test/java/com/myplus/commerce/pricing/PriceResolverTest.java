package com.myplus.commerce.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * B2B Phase 2 — the pricing precedence table.
 *
 * <p>Pure logic, no Spring, so it runs on every {@code mvn test}. This is where the risk of the slice lives:
 * a wrong precedence silently charges a trade customer the wrong price on every sale, and the only symptom is
 * an argument at the counter weeks later.
 */
class PriceResolverTest {

    private static final Long PANADOL = 1L;
    private static final Long ANTIBIOTICS = 7L;
    private static final Long ALI = 100L;
    private static final BigDecimal CATALOG = new BigDecimal("100.00");

    private static PriceRule rule(Long id, PriceRule.Scope scope, Long customerId, String type,
                                  PriceRule.Target target, Long productId, Long categoryId,
                                  PriceRule.Mode mode, String value) {
        PriceRule r = new PriceRule();
        r.setId(id);
        r.setScope(scope);
        r.setCustomerId(customerId);
        r.setCustomerType(type);
        r.setTarget(target);
        r.setProductId(productId);
        r.setCategoryId(categoryId);
        r.setMode(mode);
        r.setValue(new BigDecimal(value));
        return r;
    }

    private static PriceRule customerProduct(Long id, String value) {
        return rule(id, PriceRule.Scope.CUSTOMER, ALI, null, PriceRule.Target.PRODUCT, PANADOL, null,
                PriceRule.Mode.FIXED, value);
    }

    private static PriceRule customerCategory(Long id, String pct) {
        return rule(id, PriceRule.Scope.CUSTOMER, ALI, null, PriceRule.Target.CATEGORY, null, ANTIBIOTICS,
                PriceRule.Mode.PERCENT, pct);
    }

    private static PriceRule tierProduct(Long id, String pct) {
        return rule(id, PriceRule.Scope.TYPE, null, "WHOLESALE", PriceRule.Target.PRODUCT, PANADOL, null,
                PriceRule.Mode.PERCENT, pct);
    }

    private static PriceRule tierCategory(Long id, String pct) {
        return rule(id, PriceRule.Scope.TYPE, null, "WHOLESALE", PriceRule.Target.CATEGORY, null, ANTIBIOTICS,
                PriceRule.Mode.PERCENT, pct);
    }

    private static final BasketLine LINE =
            new BasketLine(PANADOL, ANTIBIOTICS, BigDecimal.ONE, CATALOG);

    private static final PricingContext ALI_WHOLESALE =
            new PricingContext(ALI, "WHOLESALE", LocalDate.of(2026, 6, 15));

    private static PricedLine price(List<PriceRule> rules, PricingContext ctx) {
        return PriceResolver.resolveOne(rules, ctx, LINE);
    }

    @Nested
    @DisplayName("precedence — most specific wins, and only one rule applies")
    class Precedence {

        @Test
        @DisplayName("customer × product beats everything")
        void customerProductWins() {
            PricedLine p = price(Arrays.asList(
                    tierCategory(4L, "5"), tierProduct(3L, "10"),
                    customerCategory(2L, "8"), customerProduct(1L, "92.00")), ALI_WHOLESALE);
            assertEquals(0, p.unitPrice().compareTo(new BigDecimal("92.00")));
            assertEquals(PricedLine.CONTRACT, p.source());
            assertEquals(1L, p.ruleId());
        }

        @Test
        @DisplayName("customer × category beats any tier rule")
        void customerCategoryBeatsTier() {
            PricedLine p = price(Arrays.asList(
                    tierProduct(3L, "10"), tierCategory(4L, "5"), customerCategory(2L, "8")), ALI_WHOLESALE);
            assertEquals(0, p.unitPrice().compareTo(new BigDecimal("92.00")), "8% off 100");
            assertEquals(PricedLine.CONTRACT, p.source());
            assertEquals(2L, p.ruleId());
        }

        @Test
        @DisplayName("tier × product beats tier × category")
        void tierProductBeatsTierCategory() {
            PricedLine p = price(Arrays.asList(tierCategory(4L, "5"), tierProduct(3L, "10")), ALI_WHOLESALE);
            assertEquals(0, p.unitPrice().compareTo(new BigDecimal("90.00")));
            assertEquals(PricedLine.TIER, p.source());
            assertEquals(3L, p.ruleId());
        }

        @Test
        @DisplayName("rules NEVER stack — 10% and 5% is not 14.5%")
        void rulesDoNotStack() {
            // The headline invariant. Two applicable rules must yield ONE price, not a compounded one.
            PricedLine p = price(Arrays.asList(tierProduct(3L, "10"), tierCategory(4L, "5")), ALI_WHOLESALE);
            assertEquals(0, p.unitPrice().compareTo(new BigDecimal("90.00")),
                    "only the most specific rule applies");
        }

        @Test
        @DisplayName("no rules at all → catalog price, no rule id — today's behaviour for every shop")
        void noRules() {
            PricedLine p = price(Collections.emptyList(), ALI_WHOLESALE);
            assertEquals(0, p.unitPrice().compareTo(CATALOG));
            assertEquals(PricedLine.CATALOG, p.source());
            assertNull(p.ruleId());
        }

        @Test
        @DisplayName("a rule for ANOTHER customer never applies")
        void otherCustomersRule() {
            PriceRule someoneElse = customerProduct(1L, "10.00");
            someoneElse.setCustomerId(999L);
            PricedLine p = price(Collections.singletonList(someoneElse), ALI_WHOLESALE);
            assertEquals(PricedLine.CATALOG, p.source());
        }

        @Test
        @DisplayName("a tier rule does not apply to a customer of a different type")
        void otherTier() {
            PricedLine p = price(Collections.singletonList(tierProduct(3L, "10")),
                    new PricingContext(ALI, "WALK_IN", LocalDate.of(2026, 6, 15)));
            assertEquals(PricedLine.CATALOG, p.source(), "a walk-in must not get wholesale pricing");
        }

        @Test
        @DisplayName("a walk-in with no account still gets tier rules if their type matches, but no contract")
        void anonymousCustomer() {
            PricingContext noAccount = new PricingContext(null, "WHOLESALE", LocalDate.of(2026, 6, 15));
            assertEquals(PricedLine.TIER,
                    price(Collections.singletonList(tierProduct(3L, "10")), noAccount).source());
            assertEquals(PricedLine.CATALOG,
                    price(Collections.singletonList(customerProduct(1L, "92.00")), noAccount).source(),
                    "a contract needs an identified customer");
        }

        @Test
        @DisplayName("customer type matching is case-insensitive")
        void caseInsensitiveType() {
            PricingContext lower = new PricingContext(ALI, "wholesale", LocalDate.of(2026, 6, 15));
            assertEquals(PricedLine.TIER, price(Collections.singletonList(tierProduct(3L, "10")), lower).source());
        }
    }

    @Nested
    @DisplayName("ties are deterministic")
    class Ties {

        @Test
        @DisplayName("equal specificity → higher priority wins")
        void priorityWins() {
            PriceRule low = tierProduct(3L, "10");
            PriceRule high = tierProduct(4L, "20");
            high.setPriority(5);
            assertEquals(4L, price(Arrays.asList(low, high), ALI_WHOLESALE).ruleId());
        }

        @Test
        @DisplayName("equal specificity AND priority → lowest id, so two tills never disagree")
        void lowestIdWins() {
            // Without this the winner depends on the order the database returned, and the same basket could
            // price differently on two tills — the worst kind of pricing bug, because it is intermittent.
            PricedLine a = price(Arrays.asList(tierProduct(9L, "20"), tierProduct(3L, "10")), ALI_WHOLESALE);
            PricedLine b = price(Arrays.asList(tierProduct(3L, "10"), tierProduct(9L, "20")), ALI_WHOLESALE);
            assertEquals(3L, a.ruleId());
            assertEquals(a.ruleId(), b.ruleId(), "input order must not change the answer");
        }

        @Test
        @DisplayName("an unsaved rule (null id) loses every tie rather than winning it")
        void nullIdLoses() {
            PriceRule unsaved = tierProduct(null, "50");
            assertEquals(3L, price(Arrays.asList(unsaved, tierProduct(3L, "10")), ALI_WHOLESALE).ruleId());
        }
    }

    @Nested
    @DisplayName("validity dates")
    class Dates {

        private PriceRule dated(String from, String to) {
            PriceRule r = tierProduct(3L, "10");
            if (from != null) r.setStartsOn(LocalDate.parse(from));
            if (to != null) r.setEndsOn(LocalDate.parse(to));
            return r;
        }

        @Test
        @DisplayName("both bounds are INCLUSIVE — a rule ending on the 31st works on the 31st")
        void inclusiveBounds() {
            assertTrue(dated("2026-06-15", "2026-06-15").isLiveOn(LocalDate.of(2026, 6, 15)));
        }

        @Test
        @DisplayName("expired → falls through to catalog, silently, no error")
        void expired() {
            PricedLine p = price(Collections.singletonList(dated(null, "2026-01-01")), ALI_WHOLESALE);
            assertEquals(PricedLine.CATALOG, p.source());
        }

        @Test
        @DisplayName("not started yet → catalog")
        void future() {
            assertEquals(PricedLine.CATALOG,
                    price(Collections.singletonList(dated("2027-01-01", null)), ALI_WHOLESALE).source());
        }

        @Test
        @DisplayName("no dates → always live, which is the common case")
        void noDates() {
            assertTrue(tierProduct(3L, "10").isLiveOn(LocalDate.now()));
        }

        @Test
        @DisplayName("inactive is ignored even inside its dates")
        void inactive() {
            PriceRule r = dated("2026-01-01", "2026-12-31");
            r.setActive(false);
            assertEquals(PricedLine.CATALOG, price(Collections.singletonList(r), ALI_WHOLESALE).source());
        }
    }

    @Nested
    @DisplayName("value handling")
    class Values {

        @Test
        @DisplayName("FIXED 0 is a real price (a giveaway), not 'no rule'")
        void fixedZero() {
            PricedLine p = price(Collections.singletonList(customerProduct(1L, "0")), ALI_WHOLESALE);
            assertEquals(0, p.unitPrice().compareTo(BigDecimal.ZERO));
            assertEquals(PricedLine.CONTRACT, p.source(), "a deliberate giveaway still came from the rule");
        }

        @Test
        @DisplayName("PERCENT 100 is free; over 100 or negative is rejected, falling back to catalog")
        void percentBounds() {
            assertEquals(0, price(Collections.singletonList(tierProduct(3L, "100")), ALI_WHOLESALE)
                    .unitPrice().compareTo(new BigDecimal("0.00")));
            assertEquals(PricedLine.CATALOG,
                    price(Collections.singletonList(tierProduct(3L, "150")), ALI_WHOLESALE).source(),
                    "a 150% discount would be a negative price");
            assertEquals(PricedLine.CATALOG,
                    price(Collections.singletonList(tierProduct(3L, "-5")), ALI_WHOLESALE).source(),
                    "a negative discount is a markup nobody asked for");
        }

        @Test
        @DisplayName("a null value falls back rather than throwing on the checkout path")
        void nullValue() {
            PriceRule r = tierProduct(3L, "10");
            r.setValue(null);
            assertEquals(PricedLine.CATALOG, price(Collections.singletonList(r), ALI_WHOLESALE).source());
        }

        @Test
        @DisplayName("percentages round to 2dp, half-up, like every other money figure")
        void rounding() {
            BasketLine odd = new BasketLine(PANADOL, ANTIBIOTICS, BigDecimal.ONE, new BigDecimal("99.99"));
            PricedLine p = PriceResolver.resolveOne(
                    Collections.singletonList(tierProduct(3L, "7.5")), ALI_WHOLESALE, odd);
            assertEquals(new BigDecimal("92.49"), p.unitPrice());
        }

        @Test
        @DisplayName("every priced line carries a reason — the point of the slice")
        void reasonAlwaysPresent() {
            assertEquals("Wholesale price −10%",
                    price(Collections.singletonList(tierProduct(3L, "10")), ALI_WHOLESALE).reason());
            assertEquals("Contract price",
                    price(Collections.singletonList(customerProduct(1L, "92")), ALI_WHOLESALE).reason());
        }
    }

    @Nested
    @DisplayName("resolve() over a basket")
    class Basket {

        @Test
        @DisplayName("returns one priced line per basket line, in order, never fewer")
        void onePerLine() {
            List<BasketLine> lines = Arrays.asList(
                    LINE,
                    new BasketLine(2L, 8L, BigDecimal.ONE, new BigDecimal("50.00")));
            List<PricedLine> out = PriceResolver.resolve(
                    Collections.singletonList(tierProduct(3L, "10")), ALI_WHOLESALE, lines);
            assertEquals(2, out.size());
            assertEquals(PANADOL, out.get(0).productId());
            assertEquals(PricedLine.TIER, out.get(0).source());
            assertEquals(PricedLine.CATALOG, out.get(1).source(), "the unmatched line still gets a price");
        }

        @Test
        @DisplayName("null inputs never throw — a pricing failure must not stop a sale")
        void nullSafety() {
            assertTrue(PriceResolver.resolve(null, null, null).isEmpty());
            assertEquals(PricedLine.CATALOG, PriceResolver.resolveOne(null, null, LINE).source());
            assertNull(PriceResolver.resolveOne(Collections.emptyList(), ALI_WHOLESALE, null));
        }
    }
}

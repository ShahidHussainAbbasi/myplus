package com.myplus.commerce.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One pricing rule, as the resolver sees it — deliberately a plain carrier, not a JPA entity.
 *
 * <p>The owning service (catalog-service) keeps these in its own table and maps rows onto this type. That is
 * what lets POS, storefront and pharmacy share one set of pricing RULES without sharing a table, exactly as
 * {@code common-credit} shares credit rules while each service keeps its own ledger.
 *
 * <p>The three kinds of rule described in the design are the same shape with different keys:
 * <pre>
 *   CUSTOMER × PRODUCT   → usually FIXED    "Ali Traders buys Panadol at 92"
 *   CUSTOMER × CATEGORY  → usually PERCENT  "Ali Traders gets 8% off antibiotics"
 *   TYPE     × PRODUCT|CATEGORY → PERCENT   "every WHOLESALE customer gets 12% off"
 * </pre>
 * They are one type because splitting them would triple the work to answer a single question ("what does this
 * customer pay for this product?") for no gain in clarity.
 */
public class PriceRule {

    /** Who the rule is about. */
    public enum Scope {
        /** A named customer — the most specific kind. */
        CUSTOMER,
        /** A whole customer type/tier (WHOLESALE, RETAILER…), keyed on Phase 0's {@code Customer.customerType}. */
        TYPE
    }

    /** What the rule is about. */
    public enum Target {
        PRODUCT,
        CATEGORY
    }

    /** How the rule sets the price. */
    public enum Mode {
        /** An absolute unit price. {@code 0} is a real price (a giveaway), not "no rule". */
        FIXED,
        /** A percentage off the catalog price. */
        PERCENT
    }

    private Long id;
    private Scope scope;
    private Long customerId;
    private String customerType;
    private Target target;
    private Long productId;
    private Long categoryId;
    private Mode mode;
    private BigDecimal value;
    private int priority;
    private boolean active = true;
    private LocalDate startsOn;
    private LocalDate endsOn;

    public PriceRule() {
    }

    /** True when this rule is live on {@code on} — no dates means always live, which is the common case. */
    public boolean isLiveOn(LocalDate on) {
        if (!active) {
            return false;
        }
        if (on == null) {
            return true;
        }
        if (startsOn != null && on.isBefore(startsOn)) {
            return false;
        }
        // Both bounds are INCLUSIVE: a rule that "ends on the 31st" is expected to work on the 31st.
        return endsOn == null || !on.isAfter(endsOn);
    }

    /**
     * How specific this rule is; higher wins. A named customer always beats a tier, and a named product
     * always beats a category, so a customer×product contract (3) outranks a customer×category deal (2),
     * which outranks a tier×product rule (1), which outranks a tier×category rule (0).
     */
    public int specificity() {
        int s = 0;
        if (scope == Scope.CUSTOMER) {
            s += 2;
        }
        if (target == Target.PRODUCT) {
            s += 1;
        }
        return s;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Scope getScope() { return scope; }
    public void setScope(Scope scope) { this.scope = scope; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getCustomerType() { return customerType; }
    public void setCustomerType(String customerType) { this.customerType = customerType; }

    public Target getTarget() { return target; }
    public void setTarget(Target target) { this.target = target; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDate getStartsOn() { return startsOn; }
    public void setStartsOn(LocalDate startsOn) { this.startsOn = startsOn; }

    public LocalDate getEndsOn() { return endsOn; }
    public void setEndsOn(LocalDate endsOn) { this.endsOn = endsOn; }
}

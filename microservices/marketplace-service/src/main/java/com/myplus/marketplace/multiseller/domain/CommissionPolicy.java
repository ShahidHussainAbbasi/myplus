package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;

import com.myplus.commerce.domain.Money;

/**
 * How the operator's commission on a line is computed (source §7 "commission rules", §15).
 *
 * <p>The source's worked example uses a flat Rs. 500; a percentage is the common case. The basis is a policy
 * decision (design R-MKT-8), so it is data here rather than a constant in the calculator.
 */
public record CommissionPolicy(Basis basis, BigDecimal rate, BigDecimal fixedAmount) {

    public enum Basis {
        /** rate × (customer amount − delivery fee) */
        ITEMS,
        /** rate × customer amount */
        ITEMS_PLUS_DELIVERY,
        /** a fixed amount per line */
        FIXED
    }

    public CommissionPolicy {
        if (basis == null) throw new IllegalArgumentException("basis is required");
        if (basis == Basis.FIXED) {
            if (fixedAmount == null || fixedAmount.signum() < 0)
                throw new MarketplaceRuleException("INVALID_COMMISSION", "A fixed commission must be zero or more.");
        } else if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new MarketplaceRuleException("INVALID_COMMISSION", "A commission rate must be between 0% and 100%.");
        }
    }

    public static CommissionPolicy percentOfItems(BigDecimal rate) {
        return new CommissionPolicy(Basis.ITEMS, rate, null);
    }

    public static CommissionPolicy fixed(BigDecimal amount) {
        return new CommissionPolicy(Basis.FIXED, null, amount);
    }

    /**
     * The commission in money, 2 dp HALF_UP.
     *
     * <p>Not {@link Money#multiply}: that scales BOTH operands to 2 dp first, which would turn a 12.5% rate into
     * 13%. The rate is applied at full precision and only the product is rounded.
     */
    public BigDecimal commissionOn(BigDecimal customerAmount, BigDecimal deliveryFee) {
        return switch (basis) {
            case FIXED -> Money.scale(fixedAmount);
            case ITEMS -> applyRate(Money.subtract(customerAmount, deliveryFee));
            case ITEMS_PLUS_DELIVERY -> applyRate(Money.nz(customerAmount));
        };
    }

    private BigDecimal applyRate(BigDecimal base) {
        return base.multiply(rate).setScale(Money.SCALE, Money.ROUNDING);
    }
}
